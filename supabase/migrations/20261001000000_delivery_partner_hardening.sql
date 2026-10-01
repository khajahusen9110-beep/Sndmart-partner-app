-- Delivery partner app hardening.
--
-- The partner app now performs every state change through SECURITY DEFINER RPCs that
-- check ownership and state. This migration closes the direct-table paths that let a
-- partner bypass those checks (e.g. PATCH status='delivered' without the customer OTP,
-- inserting payout requests above the available balance, or forging cash ledger rows).
--
-- Review before applying: other apps sharing this database must not rely on the
-- partner-side direct writes removed below.

begin;

-- 1. Assignments: partners may no longer UPDATE rows directly (RPCs bypass RLS as owner).
drop policy if exists assignments_partner_update on public.delivery_assignments;
create policy assignments_admin_update on public.delivery_assignments
  for update
  using (is_admin())
  with check (is_admin());

-- 2. Orders: remove the delivery-partner branch of the update policy. Partners move orders
--    forward only through delivery_partner_* RPCs.
drop policy if exists orders_role_update on public.orders;
create policy orders_role_update on public.orders
  for update
  using (
    is_admin()
    or customer_id = auth.uid()
    or ((vendor_id = current_vendor_id())
        and ((payment_method <> 'upi'::payment_method) or (payment_status = 'paid'::payment_status)))
  );

-- 3. Payout requests only through delivery_partner_request_payment (it enforces the balance).
drop policy if exists "delivery partners create own payment requests" on public.delivery_partner_payment_requests;

-- 4. Cash ledger: no direct partner inserts. 'collected' rows are written by
--    delivery_partner_complete_delivery; remittances go through the RPC below.
drop policy if exists cash_ledger_partner_insert on public.delivery_partner_cash_ledger;

create or replace function public.delivery_partner_request_cash_remittance(p_amount numeric, p_note text default null)
returns public.delivery_partner_cash_ledger
language plpgsql
security definer
set search_path to 'public'
as $function$
declare d public.delivery_partners; in_hand numeric; r public.delivery_partner_cash_ledger;
begin
  select * into d from public.delivery_partners where user_id = auth.uid() and is_active = true limit 1;
  if d.id is null then raise exception 'Delivery partner not found'; end if;
  if p_amount is null or p_amount <= 0 then raise exception 'Invalid amount'; end if;

  select coalesce(sum(case when entry_type = 'collected' then amount else -amount end), 0)
    into in_hand
    from public.delivery_partner_cash_ledger
   where delivery_partner_id = d.id and status <> 'rejected';
  if p_amount > in_hand then raise exception 'Amount exceeds cash in hand'; end if;

  insert into public.delivery_partner_cash_ledger(delivery_partner_id, entry_type, amount, status, note)
  values (d.id, 'remitted', p_amount, 'pending', nullif(trim(p_note), ''))
  returning * into r;
  return r;
end; $function$;

revoke all on function public.delivery_partner_request_cash_remittance(numeric, text) from public, anon;
grant execute on function public.delivery_partner_request_cash_remittance(numeric, text) to authenticated;

-- 5. set_delivery_partner_availability had no authorization check: any signed-in user could
--    toggle any partner. Restrict to admins, the partner's city admin, or the partner.
create or replace function public.set_delivery_partner_availability(p_partner_id uuid, p_online boolean, p_available boolean default true)
returns boolean
language plpgsql
security definer
set search_path to 'public'
as $function$
declare d public.delivery_partners;
begin
  select * into d from public.delivery_partners where id = p_partner_id;
  if d.id is null then return false; end if;
  if not (is_admin()
          or d.user_id = auth.uid()
          or (d.city_id is not null and is_city_admin_for(d.city_id))) then
    raise exception 'Not authorized';
  end if;
  update public.delivery_partners
     set is_online = p_online,
         is_available = case when p_online then p_available else false end,
         updated_at = now()
   where id = p_partner_id;
  return found;
end; $function$;

-- 6. Partners must not edit their own rating counters or re-point the row to another user.
create or replace function public.guard_delivery_partner_self_update()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
  if auth.uid() = old.user_id then
    new.approval_status := old.approval_status;
    new.rejection_reason := old.rejection_reason;
    new.city_id := old.city_id;
    new.is_active := old.is_active;
    new.total_deliveries := old.total_deliveries;
    new.avg_rating := old.avg_rating;
    new.total_ratings := old.total_ratings;
    new.user_id := old.user_id;
  end if;
  return new;
end; $function$;

-- 7. Pickup: lock the row, require a photo inside this assignment's storage folder, accept
--    assignments the order trigger already moved to 'out_for_delivery' before pickup, and move
--    a 'ready' order to 'out_for_delivery'.
create or replace function public.delivery_partner_mark_picked_up(p_assignment_id uuid, p_pickup_photo_url text)
returns public.delivery_assignments
language plpgsql
security definer
set search_path to 'public'
as $function$
declare a public.delivery_assignments; d public.delivery_partners; o public.orders;
begin
  if p_pickup_photo_url is null or trim(p_pickup_photo_url) = '' then raise exception 'Pickup photo is required'; end if;
  select * into a from public.delivery_assignments where id = p_assignment_id for update;
  select * into d from public.delivery_partners where id = a.delivery_partner_id;
  if a.id is null or d.user_id is distinct from auth.uid() then raise exception 'Assignment not found'; end if;
  if split_part(p_pickup_photo_url, '/', 1) <> a.id::text then raise exception 'Invalid pickup photo'; end if;
  if not (a.status = 'accepted' or (a.status = 'out_for_delivery' and a.pickup_photo_url is null)) then
    raise exception 'Assignment must be accepted first';
  end if;
  update public.delivery_assignments
     set status = 'picked_up', picked_up_at = now(), pickup_photo_url = p_pickup_photo_url, updated_at = now()
   where id = a.id
  returning * into a;
  select * into o from public.orders where id = a.order_id;
  if o.status = 'ready'::public.order_status then
    perform public.transition_order_status(o.id, 'out_for_delivery', auth.uid(), 'Delivery partner picked up order');
    select * into a from public.delivery_assignments where id = p_assignment_id;
  end if;
  return a;
end; $function$;

-- 8. Delivery: lock the row, accept 'out_for_delivery' as well as 'picked_up', and require the
--    proof photo inside this assignment's folder. OTP check and settlement are unchanged.
create or replace function public.delivery_partner_complete_delivery(p_assignment_id uuid, p_otp text, p_delivered_photo_url text)
returns public.delivery_assignments
language plpgsql
security definer
set search_path to 'public'
as $function$
declare a public.delivery_assignments; o public.orders;
begin
  if p_delivered_photo_url is null or trim(p_delivered_photo_url) = '' then raise exception 'Delivery proof photo is required'; end if;
  select * into a from public.delivery_assignments where id = p_assignment_id for update;
  if a.id is null or not exists(select 1 from public.delivery_partners d where d.id = a.delivery_partner_id and d.user_id = auth.uid() and d.is_active = true) then
    raise exception 'Assignment not found';
  end if;
  if split_part(p_delivered_photo_url, '/', 1) <> a.id::text then raise exception 'Invalid delivery proof photo'; end if;
  if a.status not in ('picked_up', 'out_for_delivery') then raise exception 'Order must be picked up first'; end if;
  if a.delivery_otp is null or a.delivery_otp <> trim(p_otp) then raise exception 'Invalid delivery OTP'; end if;
  update public.delivery_assignments
     set status = 'delivered', delivered_at = now(), delivered_photo_url = p_delivered_photo_url, updated_at = now()
   where id = a.id
  returning * into a;
  perform public.transition_order_status(a.order_id, 'delivered', auth.uid(), 'Delivery completed with OTP');
  perform public.settle_delivered_order_earnings(a.order_id);
  select * into o from public.orders where id = a.order_id;
  if o.payment_method = 'cash' then
    insert into public.delivery_partner_cash_ledger(delivery_partner_id, order_id, entry_type, amount, status)
    values (a.delivery_partner_id, o.id, 'collected', o.total_amount, 'confirmed');
  end if;
  return a;
end; $function$;

-- 9. Partner RPCs need a signed-in user; never expose them to anon.
revoke execute on function public.delivery_partner_complete_delivery(uuid, text, text) from anon;
revoke execute on function public.delivery_partner_deliver(uuid, text, text) from anon;
revoke execute on function public.delivery_partner_mark_picked_up(uuid, text) from anon;
revoke execute on function public.delivery_partner_pickup(uuid, text) from anon;
revoke execute on function public.delivery_partner_respond_assignment(uuid, boolean) from anon;

commit;
