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
| Confirm pickup (photo required) | `delivery_partner_pickup` |
| Complete delivery (OTP verified on server, earnings + COD cash settled) | `delivery_partner_deliver` |
| Payout request (balance checked on server) | `delivery_partner_request_payment` |
| Bank / UPI details | `delivery_partner_upsert_bank_details` |
| Cash remittance | `delivery_partner_request_cash_remittance` (added in `supabase/migrations`) |

`supabase/migrations/20261001000000_delivery_partner_hardening.sql` removes the direct-table
write paths that let a client bypass these checks. Review it against the customer, vendor and
admin apps that share the database, then apply it.

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
`GOOGLE_MAPS_API_KEY`. Optional repository variable `VERSION_NAME` (default `1.0.0`);
`versionCode` is the workflow run number.

The force-update screen compares `VERSION_NAME` with `app_versions.minimum_supported_version`
(platform `delivery_partner_app`), so bump `VERSION_NAME` for every store release.

R8 minification is still off; keep rules are already in `app/proguard-rules.pro`. Turn on
`isMinifyEnabled` only after smoke-testing a minified release build.
