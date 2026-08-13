# Research Notes: Issue #66 - Can't connect over public IP (OAuth/Nous hang)

## Issue Summary
- User reports hanging on "Signing in" banner when tapping "Nous" login method.
- Works fine in normal mobile browser (Chrome).
- User uses a "public IP" for the server URL.
- User mentions signing into Nous via GitHub OAuth.

## Key Observations from Codebase

### 1. OAuth/OIDC Recognition (`OAuthPopupFlow.kt`)
- Current logic requires `response_type=code`, `client_id`, and `redirect_uri`.
- It also requires a "marker" in the URL: `state`, `code_challenge`, or the words `oauth`/`authorize` in the path.
- **Potential Issue**: Some providers (like Nous or custom OIDC setups) might use different paths (e.g., `/auth`, `/login`, `/sso`) or might not use `state`/`code_challenge` in certain configurations. If not recognized, the navigation might be externalized or mismanaged.

### 2. Popup Handling (`MainActivity.kt`)
- Popups are created as hidden `WebView` instances.
- If a popup navigation is NOT recognized as a trusted OAuth flow, it is destroyed immediately, and the URL is loaded in the main `WebView` (if allowlisted) or opened in an external browser.
- **Potential Issue**: If a provider uses `window.open` but the first navigation is a "middle-man" redirect that isn't recognized as OAuth, the popup is destroyed and the flow is moved to the main frame. While usually okay, it might break WebUI state if it expected the popup to persist.
- **Critical Issue**: Hidden popups are **incapable of user interaction** (login/password). Any OAuth flow that requires a popup AND user input will hang because the user cannot see the password field.

### 3. URL Policy & Navigation (`UrlPolicy.kt`)
- `UrlPolicy` checks `allowedHosts`. For IP-based URLs, it only matches the exact IP.
- **Potential Issue**: If the OAuth provider is on a different host, it's externalized unless recognized as a trusted OAuth start. If externalized, the app never receives the callback because the browser doesn't know how to redirect back to the app (no Intent Filter for the public IP).

### 4. Third-Party Cookies (`BUG-037`)
- Third-party cookies are enabled ONLY while an OAuth flow is active.
- **Potential Issue**: If the flow is not recognized, third-party cookies remain disabled, which might break federated logins (like GitHub-via-Nous).

### 5. Mixed Content & Secure Contexts
- `mixedContentMode` is set to `NEVER_ALLOW`.
- IP-based `http` URLs are NOT considered Secure Contexts.
- **Potential Issue**: Some OAuth providers might require Secure Contexts for JS APIs, or might fail if `https` -> `http` redirects/POSTs are blocked.

## Hypotheses for the "Hang"

1. **Hidden Popup requiring Input**: Nous opens a popup for login. The app keeps it hidden. The user sees the WebUI "Signing in..." banner but can't see the login fields in the invisible popup.
2. **Recognition Failure -> Externalization**: The Nous authorize URL doesn't match the strict markers in `OAuthPopupFlow`. The app opens it in Chrome. Chrome handles the login but redirects back to the IP URL in Chrome, not the app. The app stays on the "Signing in" page.
3. **Port/Scheme Mismatch**: The `redirect_uri` uses a different port or scheme (http vs https) than the `serverUrl` in settings, causing `redirectsToOrigin` to fail. The flow is treated as untrusted and externalized.

## Proposed Actions
- [ ] Broaden `OAuthPopupFlow` recognition (markers).
- [ ] If an OAuth start is detected in a popup, force it into the main `WebView` (visible) so the user can interact with it.
- [ ] Allow more leniency in `hasSameOrigin` for callback verification (e.g., ignore http/https mismatch if the host is a private/public IP).
- [ ] Investigate if `MIXED_CONTENT_COMPATIBILITY_MODE` is safer for these environments.
