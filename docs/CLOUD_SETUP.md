# Cloud targets: Google Drive and OneDrive

WakiDownload can save into Google Drive and OneDrive next to the local Downloads folder. Both use
the account's own sign-in (OAuth 2.0); the app never sees a password and stores no secret.

## Google Drive (done, registered 2026-09-12)

- Mechanism: Play Services `AuthorizationClient` with scope `drive.file` (the app sees only what
  it created). The device's signed-in Google accounts are offered in the system chooser.
- Registration: Google identifies the app by package name + signing certificate, not by a client
  id in the APK. WakiDownload is registered as a Firebase Android app in the `waki-brain` Google
  Cloud project (number 555785877411) with both certificate SHA-1s (release
  `d4c54735e9ef2d337a07ce76abfc724f6d01b16c`, debug `1730816fedbd97fbecb5e52f9f27e34728e1ef51`),
  Drive API enabled. That project already had an OAuth consent screen (Waki Outlook Viewer lives
  there), which is why it was chosen over a brand-new project: consent screens cannot be created
  from the command line.
- If sign-in ever fails with `DEVELOPER_ERROR` (code 10), an Android OAuth client is missing for
  the cert: Cloud Console, project waki-brain, APIs & Services, Credentials, Create OAuth client ID,
  type Android, package `dev.wakilabs.wakidownload`, the SHA-1 above. One minute, no rebuild.

## OneDrive (registered 2026-09-13)

Registered as app `WakiDownload`, Application (client) ID `323c4a65-053a-4b3e-8738-989884bbc4dc`,
in the Default Directory (tenant `a9942d58-bf11-468e-91d0-2b6da95fe81b`) that belongs to the
personal account wwahmed.private@outlook.com. Audience "All Microsoft account users", platform
"Public client/native", redirect `wakidownload://oauth/microsoft`. The registration's home
directory does not matter to the OneDrive user: waqinator@hotmail.com (or any Microsoft account)
signs in to the app and reaches its own OneDrive. The client id is in
`~/.wakilabs-keystores/WakiDownload.oauth.properties` and compiled into the APK.

Background: waqinator@hotmail.com owns tenant `b9ee64b1-d8dd-44b0-999e-9f5923d7b03d`, whose only
MFA method (Authenticator) was lost; Azure support request `2609130040000310` (filed from the
private account's subscription, 2026-09-13) asks Microsoft to reset it or delete the tenant.

### Original steps, kept for reference

- Mechanism: plain OAuth 2.0 authorization code + PKCE in the browser against
  `login.microsoftonline.com/common` (personal and work accounts), scopes
  `Files.ReadWrite offline_access User.Read`, Microsoft Graph uploads. Public client, no secret,
  no MSAL library. Refresh token in app-private preferences.
- Registration (about two minutes):
  1. https://entra.microsoft.com, App registrations, New registration.
  2. Name `WakiDownload`. Supported account types: **Personal Microsoft accounts and any
     organizational directory** (needed for a personal OneDrive).
  3. Redirect URI: platform **Mobile and desktop applications**, custom URI
     `wakidownload://oauth/microsoft`.
  4. After creation: Authentication, "Allow public client flows" = Yes. API permissions are
     requested at sign-in time (Files.ReadWrite, User.Read, offline_access), nothing to pre-grant.
  5. Copy the **Application (client) ID** into
     `~/.wakilabs-keystores/WakiDownload.oauth.properties` as `microsoft.clientId=...` and ship a
     new build. Until then the OneDrive row shows "Needs setup".

## Personal Vault

Microsoft Graph cannot read or write OneDrive Personal Vault; Microsoft documents it as
unsupported for third-party apps. NSFW downloads therefore go to the OneDrive folder
`<OneDrive folder>/<NSFW subfolder>` (default `WakiDownload/NSFW`), or to any other target you
choose under "NSFW downloads". The only possible Vault route is the Files picker: "Add a folder
from Files" opens the system picker, where the OneDrive app is a provider; if it lists the Vault
while it is unlocked, that folder can be picked and set as the NSFW target. Untested.

## Custom folders (Files picker)

"Add a folder from Files" uses the Storage Access Framework: any DocumentsProvider on the phone,
including the Google Drive and OneDrive apps themselves, can hand over a folder. This path needs
no registration at all and rides on the apps' own sign-in.
