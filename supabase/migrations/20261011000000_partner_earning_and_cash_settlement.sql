-- Delivery partner: per-order earning set by the city admin, COD cash limit and UPI settlement.
--
-- * city_delivery_settings gets:
--     partner_earning_per_order  credited to the partner's wallet for every delivered order
--                                (NULL = old behaviour: the order's delivery fee)
--     partner_cash_limit         COD cash in hand at which the partner is blocked (NULL = no limit)
--     settlement_upi_id / _name  where partners pay their cash in hand
-- * Cash in hand = collected COD - settlements the city admin has VERIFIED. A pending
--   settlement does not unblock the partner.
-- * A blocked partner cannot go online or accept an offer, and is set offline as soon as the
--   limit is reached. Deliveries already accepted can still be completed.

-- 1. Settings and ledger columns -------------------------------------------------------------
alter table public.city_delivery_settings
  add column if not exists partner_earning_per_order numeric
    check (partner_earning_per_order is null or partner_earning_per_order >= 0),
  add column if not exists partner_cash_limit numeric
    check (partner_cash_limit is null or partner_cash_limit > 0),
  add column if not exists settlement_upi_id text,
  add column if not exists settlement_upi_name text;

alter table public.delivery_partner_cash_ledger
  add column if not exists utr text,
  add column if not exists paid_to_upi text;

-- The same UPI transaction cannot settle twice (a rejected one may be resubmitted).
create unique index if not exists delivery_partner_cash_ledger_utr_uniq
  on public.delivery_partner_cash_ledger (upper(utr))
  where utr is not null and status <> 'rejected';

-- Partners used to be able to insert any ledger row (even a "confirmed" settlement).
-- Keep direct inserts only for pending remittance requests from older app builds.
drop policy if exists cash_ledger_partner_insert on public.delivery_partner_cash_ledger;
create policy cash_ledger_partner_insert on public.delivery_partner_cash_ledger
  for insert to authenticated
  with check (
    entry_type = 'remitted' and status = 'pending' and confirmed_by is null and confirmed_at is null
    and exists (select 1 from public.delivery_partners dp
                where dp.id = delivery_partner_cash_ledger.delivery_partner_id and dp.user_id = auth.uid())
  );

-- 2. Cash state (internal) -------------------------------------------------------------------
create or replace function public.delivery_partner_cash_state(p_partner_id uuid)
returns table(cash_in_hand numeric, pending_settlement numeric, cash_limit numeric, blocked boolean)
language sql stable security definer set search_path = public
as $$
  with l as (
    select
      coalesce(sum(amount) filter (where entry_type = 'collected' and status <> 'rejected'), 0) as collected,
      coalesce(sum(amount) filter (where entry_type = 'remitted' and status = 'confirmed'), 0) as remitted,
      coalesce(sum(amount) filter (where entry_type = 'remitted' and status = 'pending'), 0) as pending
    from public.delivery_partner_cash_ledger
    where delivery_partner_id = p_partner_id
  ), s as (
    select cds.partner_cash_limit as lim
    from public.delivery_partners d
    left join public.city_delivery_settings cds on cds.city_id = d.city_id
    where d.id = p_partner_id
  )
  select l.collected - l.remitted,
         l.pending,
         s.lim,
         (s.lim is not null and l.collected - l.remitted >= s.lim)
  from l, s;
$$;
revoke all on function public.delivery_partner_cash_state(uuid) from public, anon, authenticated;

-- 3. Partner: status for the app ------------------------------------------------------------
create or replace function public.delivery_partner_get_cash_status()
returns jsonb
language plpgsql stable security definer set search_path = public
as $$
declare
  d public.delivery_partners;
  st record;
  cds public.city_delivery_settings;
  active boolean;
  last_row public.delivery_partner_cash_ledger;
begin
  select * into d from public.delivery_partners where user_id = auth.uid() limit 1;
  if d.id is null then raise exception 'Delivery partner not found'; end if;

  select * into st from public.delivery_partner_cash_state(d.id);
  select * into cds from public.city_delivery_settings where city_id = d.city_id;
  active := exists (select 1 from public.delivery_assignments a
                    where a.delivery_partner_id = d.id
                      and a.status in ('accepted', 'picked_up', 'out_for_delivery'));
  select * into last_row from public.delivery_partner_cash_ledger
   where delivery_partner_id = d.id and entry_type = 'remitted'
   order by created_at desc limit 1;

  return jsonb_build_object(
    'cash_in_hand', coalesce(st.cash_in_hand, 0),
    'pending_settlement', coalesce(st.pending_settlement, 0),
    'cash_limit', st.cash_limit,
    'blocked', coalesce(st.blocked, false),
    'has_active_delivery', active,
    'settlement_upi_id', nullif(trim(cds.settlement_upi_id), ''),
    'settlement_upi_name', nullif(trim(cds.settlement_upi_name), ''),
    'earning_per_order', cds.partner_earning_per_order,
    'last_settlement', case when last_row.id is null then null else jsonb_build_object(
      'id', last_row.id, 'amount', last_row.amount, 'status', last_row.status,
      'utr', last_row.utr, 'note', last_row.note, 'created_at', last_row.created_at,
      'confirmed_at', last_row.confirmed_at) end
  );
end;
$$;
revoke all on function public.delivery_partner_get_cash_status() from public, anon;
grant execute on function public.delivery_partner_get_cash_status() to authenticated;

-- 4. Partner: submit a UPI settlement of the whole cash in hand ----------------------------
create or replace function public.delivery_partner_submit_cash_settlement(p_utr text)
returns public.delivery_partner_cash_ledger
language plpgsql security definer set search_path = public
as $$
declare
  d public.delivery_partners;
  st record;
  v_utr text;
  v_upi text;
  r public.delivery_partner_cash_ledger;
begin
  select * into d from public.delivery_partners where user_id = auth.uid() and is_active = true limit 1;
  if d.id is null then raise exception 'Delivery partner not found'; end if;
  -- One settlement at a time per partner.
  perform 1 from public.delivery_partners where id = d.id for update;

  v_utr := upper(regexp_replace(coalesce(p_utr, ''), '\s', '', 'g'));
  if v_utr !~ '^[A-Z0-9]{6,35}$' then
    raise exception 'Enter the UPI transaction ID (UTR) shown in your payment app';
  end if;

  if exists (select 1 from public.delivery_partner_cash_ledger
             where delivery_partner_id = d.id and entry_type = 'remitted' and status = 'pending') then
    raise exception 'A settlement is already waiting for verification';
  end if;

  select nullif(trim(settlement_upi_id), '') into v_upi
    from public.city_delivery_settings where city_id = d.city_id;
  if v_upi is null then
    raise exception 'Settlement UPI ID is not set for your city. Please contact your city admin.';
  end if;

  select * into st from public.delivery_partner_cash_state(d.id);
  if coalesce(st.cash_in_hand, 0) <= 0 then raise exception 'You have no cash to settle'; end if;

  if exists (select 1 from public.delivery_partner_cash_ledger
             where upper(utr) = v_utr and status <> 'rejected') then
    raise exception 'This transaction ID has already been used';
  end if;

  insert into public.delivery_partner_cash_ledger(delivery_partner_id, entry_type, amount, status, note, utr, paid_to_upi)
  values (d.id, 'remitted', st.cash_in_hand, 'pending', 'UPI settlement', v_utr, v_upi)
  returning * into r;
  return r;
end;
$$;
revoke all on function public.delivery_partner_submit_cash_settlement(text) from public, anon;
grant execute on function public.delivery_partner_submit_cash_settlement(text) to authenticated;

-- 5. Enforcement ----------------------------------------------------------------------------
-- No going online while blocked, whichever path sets is_online (RPCs or a direct update).
create or replace function public.guard_partner_cash_limit()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if new.is_online and not coalesce(old.is_online, false)
     and exists (select 1 from public.delivery_partner_cash_state(new.id) s where s.blocked) then
    raise exception 'Cash limit reached. Settle your cash in hand to go online.';
  end if;
  return new;
end;
$$;
drop trigger if exists trg_guard_partner_cash_limit on public.delivery_partners;
create trigger trg_guard_partner_cash_limit
  before update of is_online on public.delivery_partners
  for each row execute function public.guard_partner_cash_limit();

-- Reaching the limit sets the partner offline (no new offers); accepted deliveries continue.
create or replace function public.offline_partner_at_cash_limit()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if exists (select 1 from public.delivery_partner_cash_state(new.delivery_partner_id) s where s.blocked) then
    update public.delivery_partners
       set is_online = false, is_available = false, updated_at = now()
     where id = new.delivery_partner_id and is_online;
  end if;
  return new;
end;
$$;
drop trigger if exists trg_offline_partner_at_cash_limit on public.delivery_partner_cash_ledger;
create trigger trg_offline_partner_at_cash_limit
  after insert or update of status on public.delivery_partner_cash_ledger
  for each row execute function public.offline_partner_at_cash_limit();

-- A new or lower limit applies at once to partners already over it.
create or replace function public.offline_city_partners_at_cash_limit()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if new.partner_cash_limit is not null then
    update public.delivery_partners d
       set is_online = false, is_available = false, updated_at = now()
     where d.city_id = new.city_id and d.is_online
       and exists (select 1 from public.delivery_partner_cash_state(d.id) s where s.blocked);
  end if;
  return new;
end;
$$;
drop trigger if exists trg_offline_city_partners_at_cash_limit on public.city_delivery_settings;
create trigger trg_offline_city_partners_at_cash_limit
  after insert or update of partner_cash_limit on public.city_delivery_settings
  for each row execute function public.offline_city_partners_at_cash_limit();

revoke all on function public.guard_partner_cash_limit() from public, anon, authenticated;
revoke all on function public.offline_partner_at_cash_limit() from public, anon, authenticated;
revoke all on function public.offline_city_partners_at_cash_limit() from public, anon, authenticated;

-- Accepting a new offer is refused while blocked.
create or replace function public.delivery_partner_respond_assignment(p_assignment_id uuid, p_accept boolean)
returns delivery_assignments
language plpgsql security definer set search_path to 'public'
as $function$
declare a public.delivery_assignments; o public.orders;
begin
  select * into a from public.delivery_assignments where id=p_assignment_id;
  if a.id is null or not exists(select 1 from public.delivery_partners d where d.id=a.delivery_partner_id and d.user_id=auth.uid() and d.is_active=true) then raise exception 'Assignment not found'; end if;
  if a.status<>'assigned' then raise exception 'Assignment already processed'; end if;

  if not p_accept then
    update public.delivery_assignments set status='rejected',updated_at=now() where id=a.id returning * into a;
    update public.delivery_partners set is_available=true, updated_at=now() where id=a.delivery_partner_id;
    update public.orders set tried_delivery_partner_ids = array_append(tried_delivery_partner_ids, a.delivery_partner_id) where id=a.order_id;
    perform public.assign_nearest_delivery_partner(a.order_id);
    return a;
  end if;

  if exists (select 1 from public.delivery_partner_cash_state(a.delivery_partner_id) s where s.blocked) then
    raise exception 'Cash limit reached. Settle your cash in hand to accept new orders.';
  end if;

  update public.delivery_assignments set status='accepted',accepted_at=now(),updated_at=now() where id=a.id returning * into a;
  select * into o from public.orders where id=a.order_id;
  if o.status='ready'::public.order_status then perform public.transition_order_status(o.id,'out_for_delivery',auth.uid(),'Delivery partner accepted assignment'); end if;
  return a;
end; $function$;

-- 6. Earning per order -----------------------------------------------------------------------
create or replace function public.settle_delivered_order_earnings(p_order_id uuid)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
declare o public.orders; a public.delivery_assignments; existing uuid; dp_earning numeric;
begin
 select * into o from public.orders where id=p_order_id for update;
 if o.id is null or o.status<>'delivered'::public.order_status then raise exception 'Order is not delivered'; end if;
 select * into a from public.delivery_assignments where order_id=o.id and status='delivered' order by created_at desc limit 1;
 if not (is_super_admin() or is_city_admin_for(o.city_id) or exists(select 1 from public.delivery_partners d where d.id=a.delivery_partner_id and d.user_id=auth.uid())) then raise exception 'Settlement access denied'; end if;
 select id into existing from public.vendor_wallet_transactions where order_id=o.id and type in ('credit','order_credit') limit 1;
 if existing is null and o.vendor_id is not null then
   insert into public.vendor_wallet_transactions(vendor_id,order_id,type,amount,description)
   values(o.vendor_id,o.id,'credit',coalesce(o.subtotal,0),'Order amount credited after delivery (commission deducted at withdrawal)');
 end if;
 if a.id is not null then
   select id into existing from public.delivery_partner_earnings where order_id=o.id limit 1;
   if existing is null then
     -- City admin's per-order amount; cities that have not set one keep the delivery fee.
     select cds.partner_earning_per_order into dp_earning
       from public.city_delivery_settings cds where cds.city_id = o.city_id;
     dp_earning := coalesce(dp_earning, o.delivery_fee, 0);
     insert into public.delivery_partner_earnings(delivery_partner_id,order_id,delivery_fee,incentive,penalty,net_earning,status) values(a.delivery_partner_id,o.id,dp_earning,0,0,dp_earning,'pending');
   end if;
 end if;
 return jsonb_build_object('order_id',o.id,'vendor_amount',o.vendor_amount,'delivery_fee',o.delivery_fee,'partner_earning',dp_earning,'settled',true);
end; $function$;
