-- PHASE 2 - apply ONLY after every delivery partner runs the RPC-only app.
-- Old app versions PATCH delivery_assignments/orders directly and insert payout/cash rows
-- directly; this phase blocks those writes.
--
-- Before applying, force the upgrade so no old build is left in use:
--   update public.app_versions
--      set minimum_supported_version = '<VERSION_NAME of the RPC-only release>', force_update = true
--    where platform = 'delivery_partner_app';

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

-- 4. Cash ledger: no direct partner inserts (remittances go through delivery_partner_request_cash_remittance).
drop policy if exists cash_ledger_partner_insert on public.delivery_partner_cash_ledger;

commit;
