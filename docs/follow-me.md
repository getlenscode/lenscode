# Follow Me (non-obvious)

Non-obvious requirements for the Follow Me layout.

- `FollowingSubspace` + `FollowTarget.ArDevice` must use `FollowBehavior.Soft()`. `ArDevice` is not compatible with `Tight` (nothing renders). The layout is pushed ~0.6 m in front of the head (`followMeDistanceM`).
- Needs the `HEAD_TRACKING` permission and `DeviceTrackingMode.SPATIAL`.
- Debug builds: `adb shell am broadcast -a com.lenscode.app.DEBUG_FOLLOW_ME --ez on true --ef m 0.6` toggles it and sets the distance live.
