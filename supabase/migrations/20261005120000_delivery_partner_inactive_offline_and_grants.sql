-- Deactivated partners stay "online" forever: their app keeps sending location, which
-- delivery_partner_update_location rejects ("Delivery partner not found or inactive"),
-- and auto_offline_stale_delivery_partners skips them while they hold an open assignment.
-- Safe with every app build.

begin;

-- 1. Deactivating a partner (any path: RPC, admin panel, SQL) also takes them offline.
create or replace function public.force_offline_when_partner_inactive()
returns trigger
language plpgsql
set search_path to 'public'
as $$
begin
  if new.is_active = false then
    new.is_online := false;
    new.is_available := false;
  end if;
  return new;
end; $$;

revoke all on function public.force_offline_when_partner_inactive() from public, anon, authenticated;

drop trigger if exists trg_force_offline_when_partner_inactive on public.delivery_partners;
create trigger trg_force_offline_when_partner_inactive
  before insert or update of is_active, is_online, is_available on public.delivery_partners
  for each row execute function public.force_offline_when_partner_inactive();

-- 2. Existing inactive rows that are still marked online.
update public.delivery_partners
   set is_online = false, is_available = false, updated_at = now()
 where is_active = false and (is_online or is_available);

-- 3. Bank details: partners read their row directly but write only through
--    delivery_partner_upsert_bank_details (which resets is_verified). The old ALL policy let a
--    partner PATCH is_verified = true on their own row.
drop policy if exists bank_details_partner_manage on public.delivery_partner_bank_details;
create policy bank_details_partner_read on public.delivery_partner_bank_details
  for select to authenticated
  using (exists (select 1 from public.delivery_partners d
                  where d.id = delivery_partner_bank_details.delivery_partner_id
                    and d.user_id = (select auth.uid())));

-- 4. Partner / admin SECURITY DEFINER functions that anon could call over /rpc.
--    Trigger functions keep firing (EXECUTE is only checked when a trigger is created).
do $$
declare f regprocedure;
begin
  for f in
    select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname in (
       'delivery_partner_upsert_bank_details',
       'verify_delivery_partner_bank_details',
       'city_admin_confirm_cash_remittance',
       'city_admin_create_delivery_partner_account',
       'guard_delivery_partner_self_update',
       'handle_delivery_assignment_rejection',
       'notify_city_admin_partner_application',
       'notify_city_admin_partner_withdrawal',
       'set_delivery_eta',
       'sync_delivery_assignment_on_order_status',
       'trg_record_cash_collected',
       'update_delivery_partner_rating')
  loop
    execute format('revoke execute on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated, service_role', f);
  end loop;
end $$;

commit;
