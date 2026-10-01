# Sndmart Partner

Android app for Sndmart delivery partners: KYC onboarding, live GPS while online, new-order
alerts (FCM), pickup/delivery with photo proof and customer OTP, earnings, payouts and the
COD cash ledger. Backend is Supabase (project `zennmoughfhennwgecgo`).

## How the app talks to the backend

Every state change goes through a `SECURITY DEFINER` RPC that checks the caller owns the
row and that the transition is allowed. The app never decides money, OTP or status outcomes
on its own:

| Action | RPC |
| --- | --- |
| Go online / offline | `delivery_partner_set_online` |
| Live location | `delivery_partner_update_location` |
| Accept / decline order | `delivery_partner_respond_assignment` |
| Confirm pickup (photo required) | `delivery_partner_mark_picked_up` |
| Complete delivery (OTP verified on server, earnings + COD cash settled) | `delivery_partner_complete_delivery` |
| Payout request (balance checked on server) | `delivery_partner_request_payment` |
| Bank / UPI details | `delivery_partner_upsert_bank_details` |
| Busy / available toggle | `set_delivery_partner_availability` |
| Hand over cash to hub | `delivery_partner_request_cash_remittance` |

Photo values are storage paths inside the `delivery-proof-photos` bucket and must start with
the assignment id (`<assignment_id>/pickup-<millis>.jpg`, `<assignment_id>/delivered-<millis>.jpg`).
Server error text is shown to the partner as-is.

The only remaining direct table writes are the onboarding insert into `delivery_partners`
(RLS forces it to `pending`), `profiles` (name/phone/role on onboarding), `device_tokens` and
marking `notifications` read.

The backend hardening is split in two migrations under `supabase/migrations/`:

- `..._phase1.sql` adds the cash remittance RPC and hardens the existing RPCs. Safe while old
  app builds are still in use.
- `..._phase2.sql` removes the direct-table write paths that let a client bypass the RPC
  checks. Apply it only after forcing every partner onto the RPC-only build (instructions at
  the top of the file), and after checking the customer, vendor and admin apps that share the
  database do not rely on those paths.

## Local setup

1. Copy `.env.example` to `.env` and fill in `SUPABASE_URL`, `SUPABASE_ANON_KEY` and
   `GOOGLE_MAPS_API_KEY` (Maps SDK + Directions API).
2. Put the Firebase `google-services.json` in `app/`.
3. `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`.

Debug builds use `debug.keystore` from the repo root when present (keeps the Maps/Firebase
SHA-1 stable across machines), otherwise the default Android debug key. Debug-only tools
(test order alert, sample KYC/proof photos, raw FCM token) are hidden in release builds.

## Release

CI (`.github/workflows/main.yml`) runs unit tests on every push and pull request, and on
`main` builds a signed APK + AAB. Required repository secrets: `GOOGLE_SERVICES_JSON`,
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
`GOOGLE_MAPS_API_KEY`. Optional repository variable `VERSION_NAME` (default `1.1.0`);
`versionCode` is the workflow run number.

### Shipping an update and making it required

The app reads the `app_versions` row for platform `delivery_partner_app` on launch and every
time it comes back to the foreground, and compares it with its own `VERSION_NAME`:

| Row state | What partners see |
| --- | --- |
| installed < `minimum_supported_version` | Blocking "App Update Required" screen |
| `force_update = true` and installed < `latest_version` | Blocking screen |
| installed < `latest_version` | "Update available" dialog with **Later** |
| otherwise | nothing |

**Update** opens Google Play's full-screen in-app update (only for installs from the Play
Store, once Play offers the new build to that device), otherwise the Play Store listing.

Release steps:

1. Set the repository variable `VERSION_NAME` to the new version (for example `1.2.0`) and
   run the workflow on `main`. `versionCode` is the run number, so it always increases.
2. Upload the AAB to Play Console and wait until the release is live.
3. Point the app at it:

   ```sql
   -- optional update
   update public.app_versions set latest_version = '1.2.0', updated_at = now()
    where platform = 'delivery_partner_app';

   -- required update (blocks every older build)
   update public.app_versions set latest_version = '1.2.0', minimum_supported_version = '1.2.0',
          update_message = 'Please update to keep receiving orders.', updated_at = now()
    where platform = 'delivery_partner_app';
   ```

Only make an update required after Play shows it as available. Otherwise partners are blocked
with nothing to install.

The package name `com.sndmart.delivery` is permanent once uploaded to Play.

R8 minification is still off; keep rules are already in `app/proguard-rules.pro`. Turn on
`isMinifyEnabled` only after smoke-testing a minified release build.
