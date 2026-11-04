# Make a playlist for Streaming Radio

An M3U playlist is a plain-text list of station names and audio-stream links.
One playlist can contain several stations. You do not need to code.

## Start with the template

Download [playlist-template.m3u](examples/playlist-template.m3u), then open it
in a plain-text editor such as Notepad. Its example addresses are placeholders;
replace them before loading it in the app.

```m3u
#EXTM3U
#EXTINF:-1 tvg-logo="https://example.com/station-logo.png",My station
https://example.com/live-stream
```

- **My station** is the name the app shows. Keep the comma before the name.
- The last line is the direct audio stream address, not the station homepage.
  Get a listening link from the broadcaster or another authorized source.
- `tvg-logo` is optional. It points directly to the station's image file.
  Use an HTTPS PNG or JPEG; the current release requires at least 512 pixels
  in both dimensions. This is a station logo, not changing track artwork.
- For another station, add another `#EXTINF` line and stream address below it.

Save as UTF-8 plain text with the `.m3u` extension. In Notepad, select
**Save as type: All files** so it does not add `.txt` to the filename.

## Put the file online

Upload it to a host that provides a public HTTPS link to the file itself.
The link must open or download the playlist text without signing in, a preview
page, or an approval button. A link to a sharing page is not always a direct
file link. Never put passwords or private access tokens in a public playlist.

In Streaming Radio, while parked, open **Settings → Manage playlists →
Add playlist**, paste that file link and use **Test URL** before saving.
If the test fails, the app should retain your existing saved stations.

### If you already use Dropbox

Upload your `.m3u` file and create an unrestricted shared file link. Change
`dl=0` to `dl=1` in that link; keep the other query parameters unchanged.
If it already has other parameters but no `dl`, append `&dl=1`; if there are
no parameters, append `?dl=1`. Test that the resulting link downloads the M3U
without signing in before adding it to the app. This uses Dropbox's documented
[direct-download behavior](https://help.dropbox.com/share/force-download),
not an account connection built into Streaming Radio. Provider sharing limits
and account settings can still affect availability. No live Dropbox account
or shared file has been tested for this guide.

## Change it later

Edit the hosted file and keep the same link. The app refreshes that source;
you can also use **Refresh playlists**. Changing a name or logo in your M3U
should not be confused with replacing the audio stream itself. App favorites
and local ordering follow the app's documented refresh rules.

Only include streams and images you are permitted to use. A playlist is a set
of links; it does not grant permission to rebroadcast or redistribute content.

The in-app help entry is not yet available. The template is documentation only and is not a bundled catalog.
