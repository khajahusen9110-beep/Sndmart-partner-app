-- Delivery timer starts at pickup.
--
-- trg_set_delivery_eta sets estimated_delivery_at once, when the offer is created
-- (partner -> store -> customer). When the store is slow, that time has often passed before the
-- partner even picks the order up. From now on, picking up (picked_up_at set for the first time)
-- restarts the clock: store -> customer at the usual speed/buffer of
-- calculate_delivery_eta_minutes, counted from the pickup time.
--
-- "Pickup" is the partner confirming it with the pickup photo (delivery_partner_mark_picked_up).
-- Accepting an offer on a ready order already moves the order to out_for_delivery and fills
-- picked_up_at (sync_delivery_assignment_on_order_status), so the clock is (re)started on that
-- first picked_up_at as well and again, for good, when the pickup photo is recorded.
-- The customer app's live ETA reads the same estimated_delivery_at, so it becomes more accurate.

create or replace function public.reset_delivery_eta_on_pickup()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  v_start timestamptz;
  v_pickup_lat numeric; v_pickup_lng numeric;
  v_cust_lat numeric; v_cust_lng numeric;
  v_minutes numeric;
begin
  if new.pickup_photo_url is not null and old.pickup_photo_url is null then
    v_start := now();                         -- pickup confirmed with photo
  elsif new.picked_up_at is not null and old.picked_up_at is null then
    v_start := new.picked_up_at;              -- first marked out for delivery
  else
    return new;
  end if;

  -- Same pickup point as trg_set_delivery_eta: the vendor, else the city centre (grocery hub).
  select v.latitude, v.longitude into v_pickup_lat, v_pickup_lng
    from public.orders o join public.vendors v on v.id = o.vendor_id where o.id = new.order_id;
  if v_pickup_lat is null then
    select c.center_lat, c.center_lng into v_pickup_lat, v_pickup_lng
      from public.orders o join public.cities c on c.id = o.city_id where o.id = new.order_id;
  end if;
  select ca.lat, ca.lng into v_cust_lat, v_cust_lng
    from public.orders o join public.customer_addresses ca on ca.id = o.address_id where o.id = new.order_id;

  -- No partner leg: only store -> customer.
  v_minutes := public.calculate_delivery_eta_minutes(null, null, v_pickup_lat, v_pickup_lng, v_cust_lat, v_cust_lng);
  if v_minutes is not null then
    new.estimated_delivery_minutes := v_minutes;
    new.estimated_delivery_at := v_start + make_interval(mins => v_minutes::int);
  end if;
  return new;
end;
$$;
revoke all on function public.reset_delivery_eta_on_pickup() from public, anon, authenticated;

drop trigger if exists trg_reset_delivery_eta_on_pickup on public.delivery_assignments;
create trigger trg_reset_delivery_eta_on_pickup
  before update of picked_up_at, pickup_photo_url on public.delivery_assignments
  for each row execute function public.reset_delivery_eta_on_pickup();
