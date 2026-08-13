## What changed
Fixed a critical issue where OAuth login flows (like Nous with GitHub) would hang on the "Signing in" banner. 

- **Interactive OAuth in Main WebView**: Intercepts `window.open` requests that start an OAuth flow and loads them in the primary visible WebView. This allows users to see and interact with login forms (credentials, MFA) that were previously trapped in hidden background popups.
- **Public IP Support**: Relaxed origin matching to allow `http` and `https` scheme mismatches for identical hosts/ports. This is common in public IP or local network setups where the app and OIDC provider use different protocols.
- **Broader Detection**: Updated `OAuthPopupFlow` to recognize more login path markers (`/auth`, `/login`, `/sso`) and added a fallback that identifies flows based on required parameters (`response_type=code`, `client_id`, `redirect_uri`).
- **Mixed Content Compatibility**: Enabled `MIXED_CONTENT_COMPATIBILITY_MODE` to support transitions between secure and insecure contexts during login on non-standard networks.
- **API 26 Compatibility**: Fixed a potential crash on older devices by using a more compatible `URLDecoder` overload.

## Testing
- Verified with unit tests in `OAuthPopupFlowTest.kt` covering new detection markers and parameter-based identification.
- Verified successful `assembleDebug` build.
- Verified that scheme leniency correctly identifies IP-based origins across protocols.

Fixes #66