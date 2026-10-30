# Upstream research (2026-10-20)

Repository metadata and README files were inspected through the GitHub API.
Links below identify candidates, not endorsements or compatibility claims.

| Project | Source | Findings / current use |
| --- | --- | --- |
| GL4ES | https://github.com/ptitSeb/gl4es | MIT; actual source dependency, exact commit in `upstream.json`. |
| Pojav GL4ES fork | https://github.com/PojavLauncherTeam/gl4es-114-extra | Launcher-oriented candidate; not incorporated. |
| NG-GL4ES | https://github.com/BZLZHH/NG-GL4ES | MIT with third-party components; confirmed Krypton Wrapper, now pinned and compiled by the combined build. |
| Krypton AAR | https://github.com/whitebelyash/ng-gl4es-aar | Describes itself as NG GL4ES (Krypton Wrapper) wrapped in AAR; not incorporated. |
| Krypton versions | https://github.com/fkbmr/Krypton_Wrapper-All-Version | Candidate archive; original source provenance not verified, not incorporated. |
| LTW core | https://github.com/MojoLauncher/LTW | LGPL-3.0; actual renderer source, now pinned and compiled by the combined build. |
| LTW plugin | https://github.com/csf0304/LTWRenderer | GPL-3.0; README describes a Zalith renderer plugin based on ZalithLauncher/RendererPlugin; not incorporated. A plugin is not necessarily the renderer's translation core. |
| Rust GL renderer | Unresolved | Search did not identify the intended project. Need an exact repository URL before evaluating or using code. |

Do not copy binary collections or combine engines indiscriminately. Competing
GL symbol exports, context ownership, and different shader translators need
explicit backend selection or an adapter architecture. Any GPL-derived combined
work needs a license/distribution review and corresponding-source compliance;
the MIT license here does not relicense third-party code.

Next steps: identify the exact Rust source, select one launcher version
and native bridge contract, audit its plugin schema and license, then implement
and test one adapter before adding additional backends.
