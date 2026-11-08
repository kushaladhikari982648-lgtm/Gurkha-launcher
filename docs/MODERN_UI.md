# Modern native UI — beta.10

Implements the user-approved concept in design-previews/modern-ui using Android XML/views,
not an embedded webpage. Preview placeholder accounts/profiles were not imported.

The home layout has equivalent binding IDs in portrait and landscape, all original actions,
a scrollable profile card, tools disclosure, and actual account/version data. Settings keeps
all five existing categories and every real option; horizontally scrollable tabs replace the
cramped settings rail. Custom backgrounds are retained; default scenery is no longer decoded.

Navigation uses one 160 ms fade, not stacked fragment/child bounces; Android animation disabling
and the launcher animation preference are respected. Shared controls no longer enqueue pivot
updates at every layout or scale outside their rounded bounds. TypedArrays are recycled.
Settings pages are owned by their parent fragment and remove view callbacks/adapters on teardown.
Launcher portrait/landscape recreation is enabled; game orientation/runtime behavior is unchanged.

Validation: resource/wiring regression tests, all-ABI build checks, and a real APK smoke test on an
API 29 x86_64 emulator (first-run setup, home/settings/tools, size/font recreation). CI stores
native screenshots and logs in native-ui-smoke. No real-device frame-time/FPS claim is made.
The original HTML screenshots are design references, not Android screenshots.
