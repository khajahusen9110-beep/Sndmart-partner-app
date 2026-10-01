-- Follow-up to the applied Phase 1 (applied to production on 2026-10-01).
-- Three gaps found against the live schema after the user's Phase 1 was applied.

-- 1. Partner RPCs were executable via PUBLIC (anon); restrict to signed-in users + service_role.
revoke execute on function public.delivery_partner_complete_delivery(uuid, text, text) from public, anon;
revoke execute on function public.delivery_partner_deliver(uuid, text, text) from public, anon;
revoke execute on function public.delivery_partner_mark_picked_up(uuid, text) from public, anon;
revoke execute on function public.delivery_partner_pickup(uuid, text) from public, anon;
grant execute on function public.delivery_partner_complete_delivery(uuid, text, text) to authenticated, service_role;
grant execute on function public.delivery_partner_deliver(uuid, text, text) to authenticated, service_role;
grant execute on function public.delivery_partner_mark_picked_up(uuid, text) to authenticated, service_role;
grant execute on function public.delivery_partner_pickup(uuid, text) to authenticated, service_role;

-- 2. Keep service_role / cron / internal callers (auth.uid() is null) working.
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
  if not (auth.uid() is null
          or is_admin()
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

-- 3. Guard only the partner's own top-level UPDATE, so trigger-driven updates
--    (on_order_status_changed incrementing total_deliveries) are not reverted.
create or replace function public.guard_delivery_partner_self_update()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
  if auth.uid() = old.user_id and pg_trigger_depth() = 1 then
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
