# Android integration changes

Upstream: https://github.com/a-pavlov/jed2k
Revision: bc10ef8a7c966ccde1590a1077b32f01ac94122a

* Keep endpoint ports unsigned, including ephemeral and high peer ports.
* Bound TCP packet lengths, container counts and Kad contact counts before parsing.
* Reset source discovery after server login so initial transfers do not wait a minute.
* Handle the last piece and progress correctly for exact multiples of the ED2K piece size.
* Accept the terminal empty-piece hash used by ED2K at an exact piece boundary.
* Exclude upstream's duplicate Apache Commons sources and reuse the app's Commons IO jar.

The app supplies a non-truncating random-access file handler, bounded bootstrap downloads,
per-task persistence, checked resume data, background service lifecycle and error isolation.
No upstream upload/UPnP services are enabled by this integration.
