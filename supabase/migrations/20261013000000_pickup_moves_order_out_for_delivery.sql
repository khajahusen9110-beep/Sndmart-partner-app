-- Pickup moves the order out for delivery from any pre-dispatch status.
--
-- delivery_partner_mark_picked_up only moved the order to out_for_delivery when it was 'ready'.
-- Orders without a vendor step (city grocery hub, vendor_id null) never reach 'ready', so they
-- stayed 'confirmed' after pickup: the customer app never showed the delivery OTP (it shows it
-- for out_for_delivery only) and delivery_partner_complete_delivery failed on the
-- confirmed -> delivered transition.
--
-- Now a delivery partner may move the order to out_for_delivery from confirmed / preparing /
-- ready, but from confirmed / preparing only once their assignment has the pickup photo.

create or replace function public.transition_order_status(p_order_id uuid, p_new_status text, p_actor_id uuid default null, p_note text default null)
returns boolean
language plpgsql security definer set search_path = public
as $$
declare
 o public.orders; actor uuid:=coalesce(p_actor_id,auth.uid()); new_status public.order_status;
begin
 if actor is null or auth.uid() is distinct from actor then raise exception 'Invalid actor'; end if;
 begin new_status:=p_new_status::public.order_status; exception when invalid_text_representation then raise exception 'Invalid order status'; end;
 select * into o from public.orders where id=p_order_id for update;
 if o.id is null then raise exception 'Order not found'; end if;
 if new_status=o.status then raise exception 'Order is already %',new_status; end if;

 if is_super_admin() or is_city_admin_for(o.city_id) then
   if new_status not in ('pending','confirmed','preparing','ready','out_for_delivery','delivered','cancelled','rejected') then raise exception 'Invalid admin transition'; end if;
 elsif exists(select 1 from public.vendors x where x.id=o.vendor_id and x.user_id=actor and x.is_active=true) then
   if (o.status,new_status) not in (('confirmed','preparing'),('preparing','ready')) then raise exception 'Invalid vendor transition from % to %',o.status,new_status; end if;
 elsif exists(select 1 from public.delivery_partners x where x.id=o.delivery_partner_id and x.user_id=actor and x.is_active=true) then
   if not (
        (o.status='ready' and new_status='out_for_delivery')
     or (o.status in ('confirmed','preparing') and new_status='out_for_delivery'
         and exists(select 1 from public.delivery_assignments a
                     where a.order_id=o.id and a.delivery_partner_id=o.delivery_partner_id
                       and a.pickup_photo_url is not null
                       and a.status in ('picked_up','out_for_delivery')))
     or (o.status='out_for_delivery' and new_status='delivered')
   ) then raise exception 'Invalid delivery transition from % to %',o.status,new_status; end if;
 else
   raise exception 'Not authorized to transition this order';
 end if;

 update public.orders set status=new_status,
   confirmed_at=case when new_status='confirmed' then coalesce(confirmed_at,now()) else confirmed_at end,
   delivered_at=case when new_status='delivered' then coalesce(delivered_at,now()) else delivered_at end,
   cancelled_at=case when new_status in ('cancelled','rejected') then coalesce(cancelled_at,now()) else cancelled_at end,
   updated_at=now()
 where id=o.id;
 insert into public.order_status_history(order_id,status,changed_by,note) values(o.id,new_status,actor,p_note);
 perform public.log_order_event(o.id,'status_changed',o.status::text,actor,jsonb_build_object('status',new_status::text,'note',p_note));
 return true;
end; $$;

create or replace function public.delivery_partner_mark_picked_up(p_assignment_id uuid, p_pickup_photo_url text)
returns public.delivery_assignments
language plpgsql security definer set search_path = public
as $$
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
  if o.status in ('confirmed', 'preparing', 'ready') then
    perform public.transition_order_status(o.id, 'out_for_delivery', auth.uid(), 'Delivery partner picked up order');
    select * into a from public.delivery_assignments where id = p_assignment_id;
  end if;
  return a;
end; $$;

-- Orders already picked up (photo recorded) but stuck before out_for_delivery.
-- sync_delivery_assignment_on_order_status moves their assignment to out_for_delivery too.
-- The migration runs without a user, so restrict_customer_order_update needs its internal flag.
select set_config('sndmart.internal_checkout', 'true', true);
update public.orders o
   set status = 'out_for_delivery', updated_at = now()
 where o.status in ('confirmed', 'preparing', 'ready')
   and exists (select 1 from public.delivery_assignments a
                where a.order_id = o.id and a.delivery_partner_id = o.delivery_partner_id
                  and a.pickup_photo_url is not null
                  and a.status in ('picked_up', 'out_for_delivery'));
