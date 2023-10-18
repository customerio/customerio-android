## [3.6.7](https://github.com/customerio/customerio-android/compare/3.6.6...3.6.7) (2023-10-17)


### Bug Fixes

* added exception handling for when browser doesn't exist ([#271](https://github.com/customerio/customerio-android/issues/271)) ([aaddad5](https://github.com/customerio/customerio-android/commit/aaddad53281ac22c1e8a73340fcb24e7522e4648))

### [3.6.6](https://github.com/customerio/customerio-android/compare/3.6.5...3.6.6) (2023-09-15)


### Bug Fixes

* stack-overflow caused by BQ recursion ([#251](https://github.com/customerio/customerio-android/issues/251)) ([365a5b6](https://github.com/customerio/customerio-android/commit/365a5b690ed37667dfb6782629ad56743d97904d))

### [3.6.5](https://github.com/customerio/customerio-android/compare/3.6.4...3.6.5) (2023-08-23)


### Bug Fixes

* prevent concurrency issues in in-app listeners ([#246](https://github.com/customerio/customerio-android/issues/246)) ([72dafd7](https://github.com/customerio/customerio-android/commit/72dafd7e55091dbf64719fadc1cc9ff4010a00a4))

### [3.6.4](https://github.com/customerio/customerio-android/compare/3.6.3...3.6.4) (2023-07-21)


### Bug Fixes

* in-app messaging proguard rules missing ([#241](https://github.com/customerio/customerio-android/issues/241)) ([c494bb0](https://github.com/customerio/customerio-android/commit/c494bb01ed4c3a7e552e381a88b3e27264c553ba))

### [3.6.3](https://github.com/customerio/customerio-android/compare/3.6.2...3.6.3) (2023-07-14)


### Bug Fixes

* prevent empty identifier and device token ([#240](https://github.com/customerio/customerio-android/issues/240)) ([e9b5d0c](https://github.com/customerio/customerio-android/commit/e9b5d0cf74e52dde042ffc6a6412f443821d24bf))

### [3.6.2](https://github.com/customerio/customerio-android/compare/3.6.1...3.6.2) (2023-07-13)


### Bug Fixes

* duplicate classes crash on wrappers ([#239](https://github.com/customerio/customerio-android/issues/239)) ([ead2175](https://github.com/customerio/customerio-android/commit/ead217559c675b561410ea97886b4d4dfa6af2c0))

### [3.6.1](https://github.com/customerio/customerio-android/compare/3.6.0...3.6.1) (2023-07-12)


### Bug Fixes

* migrate in-app module from gist to CIO ([#221](https://github.com/customerio/customerio-android/issues/221)) ([d6fda6d](https://github.com/customerio/customerio-android/commit/d6fda6df4c81b2499039cae857a7bbd3ccccafd6))

## [3.6.0](https://github.com/customerio/customerio-android/compare/3.5.3...3.6.0) (2023-07-07)


### Features

* expose device token ([#235](https://github.com/customerio/customerio-android/issues/235)) ([deaa916](https://github.com/customerio/customerio-android/commit/deaa9164d7a9666823dfa4e6c09e3c20d6a3cfeb))

### [3.5.3](https://github.com/customerio/customerio-android/compare/3.5.2...3.5.3) (2023-07-03)


### Bug Fixes

* expose push tracking to wrapper sdks ([#227](https://github.com/customerio/customerio-android/issues/227)) ([3bc1345](https://github.com/customerio/customerio-android/commit/3bc134507f1539329a4ed015e7b0ae5a76d109e0))

### [3.5.2](https://github.com/customerio/customerio-android/compare/3.5.1...3.5.2) (2023-06-09)


### Bug Fixes

* set auto track screen to false by default ([#216](https://github.com/customerio/customerio-android/issues/216)) ([71fcf52](https://github.com/customerio/customerio-android/commit/71fcf528bb52f7d2fcc6c9cecf686927e3c9b77b))

### [3.5.1](https://github.com/customerio/customerio-android/compare/3.5.0...3.5.1) (2023-06-01)


### Bug Fixes

* improve delivered metrics ([#204](https://github.com/customerio/customerio-android/issues/204)) ([831d1d9](https://github.com/customerio/customerio-android/commit/831d1d92c7abcf861b73e1301fbed57b0945b44b))

## [3.5.0](https://github.com/customerio/customerio-android/compare/3.4.3...3.5.0) (2023-05-26)


### Features

* dismiss in-app message ([#186](https://github.com/customerio/customerio-android/issues/186)) ([89794f3](https://github.com/customerio/customerio-android/commit/89794f32ed7d8de3f699bfa5f985c179c5b3762c))

### [3.4.3](https://github.com/customerio/customerio-android/compare/3.4.2...3.4.3) (2023-05-19)


### Bug Fixes

* minor only auto update for gist ([#210](https://github.com/customerio/customerio-android/issues/210)) ([c00d50c](https://github.com/customerio/customerio-android/commit/c00d50c22e8c0e21d445d678833fc0bb81b70d0b))

### [3.4.3](https://github.com/customerio/customerio-android/compare/3.4.2...3.4.3) (2023-05-19)


### Bug Fixes

* minor only auto update for gist ([#210](https://github.com/customerio/customerio-android/issues/210)) ([c00d50c](https://github.com/customerio/customerio-android/commit/c00d50c22e8c0e21d445d678833fc0bb81b70d0b))

### [3.4.2](https://github.com/customerio/customerio-android/compare/3.4.1...3.4.2) (2023-04-22)


### Bug Fixes

* multiple Queue instances being created ([#190](https://github.com/customerio/customerio-android/issues/190)) ([406626c](https://github.com/customerio/customerio-android/commit/406626c467bb65616fd66184bc0fc5005b3b335b))

### [3.4.1](https://github.com/customerio/customerio-android/compare/3.4.0...3.4.1) (2023-04-20)


### Bug Fixes

* push opened metrics tracked on Android 12 ([#184](https://github.com/customerio/customerio-android/issues/184)) ([d2e52fa](https://github.com/customerio/customerio-android/commit/d2e52fa35be34cf0248d3c775614f487234cf8d4))

## [3.4.0](https://github.com/customerio/customerio-android/compare/3.3.2...3.4.0) (2023-04-19)


### Features

* in app click tracking ([#187](https://github.com/customerio/customerio-android/issues/187)) ([4ad1f35](https://github.com/customerio/customerio-android/commit/4ad1f35c6ba5e67f07dd78f1169ef4b9b1ed547a))

### [3.3.2](https://github.com/customerio/customerio-android/compare/3.3.1...3.3.2) (2023-03-10)


### Bug Fixes

* remove tasks from queue with 400 http response ([#177](https://github.com/customerio/customerio-android/issues/177)) ([3ed104a](https://github.com/customerio/customerio-android/commit/3ed104a65be9cb673e4ab694335adef5d0047b8d))

### [3.3.1](https://github.com/customerio/customerio-android/compare/3.3.0...3.3.1) (2023-03-07)


### Bug Fixes

* prevent crash for file not found exception ([#178](https://github.com/customerio/customerio-android/issues/178)) ([be8a2d9](https://github.com/customerio/customerio-android/commit/be8a2d9776114a2b8b86c6972c29dc6aec5e02f1))

## [3.3.0](https://github.com/customerio/customerio-android/compare/3.2.0...3.3.0) (2023-02-22)


### Features

* add setting a in-app event listener ([#147](https://github.com/customerio/customerio-android/issues/147)) ([5fd9559](https://github.com/customerio/customerio-android/commit/5fd95590788b518c3fddcea60795c04e128c49a7))
* in-app feature no longer requires orgId ([#163](https://github.com/customerio/customerio-android/issues/163)) ([fc2a08e](https://github.com/customerio/customerio-android/commit/fc2a08eda19a2f6387790aec442512fb115e0ea0))


### Bug Fixes

* image not shown when image url in notification payload ([#172](https://github.com/customerio/customerio-android/issues/172)) ([0abdc85](https://github.com/customerio/customerio-android/commit/0abdc85ba5213f758e03a378d9374b89dd28d335))
* moved shared wrapper code ([#158](https://github.com/customerio/customerio-android/issues/158)) ([51af98f](https://github.com/customerio/customerio-android/commit/51af98f13ec1ab26fbd2bbc160a817eb19ccb080))
* remove currentRoute parameter in in-app event listener ([#159](https://github.com/customerio/customerio-android/issues/159)) ([688e4a5](https://github.com/customerio/customerio-android/commit/688e4a53a031b02a1ef81b61e328b1a35cd77381))
* rename in app listener keys ([#164](https://github.com/customerio/customerio-android/issues/164)) ([f540eaf](https://github.com/customerio/customerio-android/commit/f540eaf10310ab97e57912fe5382f39319a565af))
* set gist dependency to use latest 3.X.Y version ([#170](https://github.com/customerio/customerio-android/issues/170)) ([a019c36](https://github.com/customerio/customerio-android/commit/a019c36cc1fd37e6180170d2d5f703e2ac8c48e8))
* set gist user token incase identifier exists ([#162](https://github.com/customerio/customerio-android/issues/162)) ([44cc4d1](https://github.com/customerio/customerio-android/commit/44cc4d11fc11a2e96fcdc0fa1b83f012fb25fbd5))
* update CustomerIOFirebaseMessagingService to open ([#174](https://github.com/customerio/customerio-android/issues/174)) ([edce7f5](https://github.com/customerio/customerio-android/commit/edce7f5b640a76b1fdfa029b9b0dabea281c677c))
* upgrade dependencies ([#146](https://github.com/customerio/customerio-android/issues/146)) ([6da8b8d](https://github.com/customerio/customerio-android/commit/6da8b8d3d16fa1c9c0acdb6012271a2252b30951))
* use maven style dependency range syntax ([#171](https://github.com/customerio/customerio-android/issues/171)) ([ba83214](https://github.com/customerio/customerio-android/commit/ba83214ec1218b36bc824ddca355a6fd5041b65e))

## [3.3.0-beta.5](https://github.com/customerio/customerio-android/compare/3.3.0-beta.4...3.3.0-beta.5) (2023-02-22)


### Bug Fixes

* update CustomerIOFirebaseMessagingService to open ([#174](https://github.com/customerio/customerio-android/issues/174)) ([edce7f5](https://github.com/customerio/customerio-android/commit/edce7f5b640a76b1fdfa029b9b0dabea281c677c))

## [3.3.0-beta.4](https://github.com/customerio/customerio-android/compare/3.3.0-beta.3...3.3.0-beta.4) (2023-02-17)


### Bug Fixes

* image not shown when image url in notification payload ([#172](https://github.com/customerio/customerio-android/issues/172)) ([0abdc85](https://github.com/customerio/customerio-android/commit/0abdc85ba5213f758e03a378d9374b89dd28d335))

## [3.3.0-beta.3](https://github.com/customerio/customerio-android/compare/3.3.0-beta.2...3.3.0-beta.3) (2023-02-16)


### Bug Fixes

* use maven style dependency range syntax ([#171](https://github.com/customerio/customerio-android/issues/171)) ([ba83214](https://github.com/customerio/customerio-android/commit/ba83214ec1218b36bc824ddca355a6fd5041b65e))

## [3.3.0-beta.2](https://github.com/customerio/customerio-android/compare/3.3.0-beta.1...3.3.0-beta.2) (2023-02-16)


### Bug Fixes

* set gist dependency to use latest 3.X.Y version ([#170](https://github.com/customerio/customerio-android/issues/170)) ([a019c36](https://github.com/customerio/customerio-android/commit/a019c36cc1fd37e6180170d2d5f703e2ac8c48e8))

## [3.3.0-beta.1](https://github.com/customerio/customerio-android/compare/3.2.0...3.3.0-beta.1) (2023-02-07)


### Features

* add setting a in-app event listener ([#147](https://github.com/customerio/customerio-android/issues/147)) ([5fd9559](https://github.com/customerio/customerio-android/commit/5fd95590788b518c3fddcea60795c04e128c49a7))
* in-app feature no longer requires orgId ([#163](https://github.com/customerio/customerio-android/issues/163)) ([fc2a08e](https://github.com/customerio/customerio-android/commit/fc2a08eda19a2f6387790aec442512fb115e0ea0))


### Bug Fixes

* moved shared wrapper code ([#158](https://github.com/customerio/customerio-android/issues/158)) ([51af98f](https://github.com/customerio/customerio-android/commit/51af98f13ec1ab26fbd2bbc160a817eb19ccb080))
* remove currentRoute parameter in in-app event listener ([#159](https://github.com/customerio/customerio-android/issues/159)) ([688e4a5](https://github.com/customerio/customerio-android/commit/688e4a53a031b02a1ef81b61e328b1a35cd77381))
* rename in app listener keys ([#164](https://github.com/customerio/customerio-android/issues/164)) ([f540eaf](https://github.com/customerio/customerio-android/commit/f540eaf10310ab97e57912fe5382f39319a565af))
* set gist user token incase identifier exists ([#162](https://github.com/customerio/customerio-android/issues/162)) ([44cc4d1](https://github.com/customerio/customerio-android/commit/44cc4d11fc11a2e96fcdc0fa1b83f012fb25fbd5))
* upgrade dependencies ([#146](https://github.com/customerio/customerio-android/issues/146)) ([6da8b8d](https://github.com/customerio/customerio-android/commit/6da8b8d3d16fa1c9c0acdb6012271a2252b30951))

## [3.3.0-alpha.1](https://github.com/customerio/customerio-android/compare/3.2.0...3.3.0-alpha.1) (2023-02-06)


### Features

* add setting a in-app event listener ([#147](https://github.com/customerio/customerio-android/issues/147)) ([5fd9559](https://github.com/customerio/customerio-android/commit/5fd95590788b518c3fddcea60795c04e128c49a7))
* in-app feature no longer requires orgId ([#163](https://github.com/customerio/customerio-android/issues/163)) ([fc2a08e](https://github.com/customerio/customerio-android/commit/fc2a08eda19a2f6387790aec442512fb115e0ea0))


### Bug Fixes

* moved shared wrapper code ([#158](https://github.com/customerio/customerio-android/issues/158)) ([51af98f](https://github.com/customerio/customerio-android/commit/51af98f13ec1ab26fbd2bbc160a817eb19ccb080))
* remove currentRoute parameter in in-app event listener ([#159](https://github.com/customerio/customerio-android/issues/159)) ([688e4a5](https://github.com/customerio/customerio-android/commit/688e4a53a031b02a1ef81b61e328b1a35cd77381))
* rename in app listener keys ([#164](https://github.com/customerio/customerio-android/issues/164)) ([f540eaf](https://github.com/customerio/customerio-android/commit/f540eaf10310ab97e57912fe5382f39319a565af))
* set gist user token incase identifier exists ([#162](https://github.com/customerio/customerio-android/issues/162)) ([44cc4d1](https://github.com/customerio/customerio-android/commit/44cc4d11fc11a2e96fcdc0fa1b83f012fb25fbd5))
* upgrade dependencies ([#146](https://github.com/customerio/customerio-android/issues/146)) ([6da8b8d](https://github.com/customerio/customerio-android/commit/6da8b8d3d16fa1c9c0acdb6012271a2252b30951))

## [3.2.0](https://github.com/customerio/customerio-android/compare/3.1.1...3.2.0) (2023-02-02)


### Features

* sdk initialization re-architecture ([9e21960](https://github.com/customerio/customerio-android/commit/9e219600c45554425841d55c8ccc97891514141f))


### Bug Fixes

* cio sdk version attribute using client value ([bb90f35](https://github.com/customerio/customerio-android/commit/bb90f35061a44c1adc2ed68ed90dfa7e1860f2a4))
* user agent client support in preferences repo ([454a18e](https://github.com/customerio/customerio-android/commit/454a18eec3c4716cb809db6c91d505fa21ba5350))

## [3.2.0-beta.1](https://github.com/customerio/customerio-android/compare/3.1.1...3.2.0-beta.1) (2023-02-02)


### Features

* sdk initialization re-architecture ([9e21960](https://github.com/customerio/customerio-android/commit/9e219600c45554425841d55c8ccc97891514141f))


### Bug Fixes

* cio sdk version attribute using client value ([bb90f35](https://github.com/customerio/customerio-android/commit/bb90f35061a44c1adc2ed68ed90dfa7e1860f2a4))
* user agent client support in preferences repo ([454a18e](https://github.com/customerio/customerio-android/commit/454a18eec3c4716cb809db6c91d505fa21ba5350))

## [3.2.0-alpha.2](https://github.com/customerio/customerio-android/compare/3.2.0-alpha.1...3.2.0-alpha.2) (2022-11-30)


### Bug Fixes

* in-app messages instant delivery ([#150](https://github.com/customerio/customerio-android/issues/150)) ([a6dcf3c](https://github.com/customerio/customerio-android/commit/a6dcf3c190d25cc7052aa3e749b6258beafed1f7))

### [3.1.1](https://github.com/customerio/customerio-android/compare/3.1.0...3.1.1) (2022-11-28)


### Bug Fixes

* in-app messages instant delivery ([#150](https://github.com/customerio/customerio-android/issues/150)) ([a6dcf3c](https://github.com/customerio/customerio-android/commit/a6dcf3c190d25cc7052aa3e749b6258beafed1f7))

## [3.2.0-alpha.1](https://github.com/customerio/customerio-android/compare/3.1.0...3.2.0-alpha.1) (2022-11-17)


### Features

* sdk initialization re-architecture ([9e21960](https://github.com/customerio/customerio-android/commit/9e219600c45554425841d55c8ccc97891514141f))


### Bug Fixes

* cio sdk version attribute using client value ([bb90f35](https://github.com/customerio/customerio-android/commit/bb90f35061a44c1adc2ed68ed90dfa7e1860f2a4))
* user agent client support in preferences repo ([454a18e](https://github.com/customerio/customerio-android/commit/454a18eec3c4716cb809db6c91d505fa21ba5350))

## [3.1.0](https://github.com/customerio/customerio-android/compare/3.0.0...3.1.0) (2022-10-17)


### Features

* added shared instance for independent components ([70fa8cd](https://github.com/customerio/customerio-android/commit/70fa8cd69079c94cd20d5e9bc02e563796c5e52a))
* added support to modify notification small icon ([b93c2dc](https://github.com/customerio/customerio-android/commit/b93c2dc653c32d58da83e4a01afffef01b0fbfd1))


### Bug Fixes

* in-app messages stop delivering ([4027502](https://github.com/customerio/customerio-android/commit/40275020b73748864fab504234057170bd5b5561))
* in-app system link causes app reopen ([6349081](https://github.com/customerio/customerio-android/commit/6349081c74bf595b06dd1157a382c6c4b884ac55))
* updated json adapter usage to safe parsing ([f72280b](https://github.com/customerio/customerio-android/commit/f72280b3435be18274d81aee71303ae6ca9fda01))

## [3.1.0-beta.1](https://github.com/customerio/customerio-android/compare/3.0.0...3.1.0-beta.1) (2022-10-17)


### Features

* added shared instance for independent components ([70fa8cd](https://github.com/customerio/customerio-android/commit/70fa8cd69079c94cd20d5e9bc02e563796c5e52a))
* added support to modify notification small icon ([b93c2dc](https://github.com/customerio/customerio-android/commit/b93c2dc653c32d58da83e4a01afffef01b0fbfd1))


### Bug Fixes

* in-app messages stop delivering ([4027502](https://github.com/customerio/customerio-android/commit/40275020b73748864fab504234057170bd5b5561))
* in-app system link causes app reopen ([6349081](https://github.com/customerio/customerio-android/commit/6349081c74bf595b06dd1157a382c6c4b884ac55))
* updated json adapter usage to safe parsing ([f72280b](https://github.com/customerio/customerio-android/commit/f72280b3435be18274d81aee71303ae6ca9fda01))

## [3.1.0-alpha.2](https://github.com/customerio/customerio-android/compare/3.1.0-alpha.1...3.1.0-alpha.2) (2022-10-17)


### Bug Fixes

* in-app system link causes app reopen ([6349081](https://github.com/customerio/customerio-android/commit/6349081c74bf595b06dd1157a382c6c4b884ac55))

## [3.1.0-alpha.1](https://github.com/customerio/customerio-android/compare/3.0.0...3.1.0-alpha.1) (2022-10-10)


### Features

* added shared instance for independent components ([70fa8cd](https://github.com/customerio/customerio-android/commit/70fa8cd69079c94cd20d5e9bc02e563796c5e52a))
* added support to modify notification small icon ([b93c2dc](https://github.com/customerio/customerio-android/commit/b93c2dc653c32d58da83e4a01afffef01b0fbfd1))


### Bug Fixes

* in-app messages stop delivering ([4027502](https://github.com/customerio/customerio-android/commit/40275020b73748864fab504234057170bd5b5561))
* updated json adapter usage to safe parsing ([f72280b](https://github.com/customerio/customerio-android/commit/f72280b3435be18274d81aee71303ae6ca9fda01))

## [3.0.0](https://github.com/customerio/customerio-android/compare/2.1.1...3.0.0) (2022-10-05)


### ⚠ BREAKING CHANGES

* android 12 deep link fix

### Features

* added option to customize push notification from app ([68010f8](https://github.com/customerio/customerio-android/commit/68010f84e39872dc5b0d8cfffda8f169efeaa472))
* changes for react native package ([2f20ac3](https://github.com/customerio/customerio-android/commit/2f20ac3dd1c1ba6be215d5206b22c143e37efe94))
* in app sdk ([1036c80](https://github.com/customerio/customerio-android/commit/1036c8030259eaef1472e8c004636aee02d1af8a))
* updated client to support react native user agent ([7588526](https://github.com/customerio/customerio-android/commit/7588526bef0e7bfc130b1b5a2cc8fd915bff3483))


### Bug Fixes

* android 12 deep link fix ([fd7ae28](https://github.com/customerio/customerio-android/commit/fd7ae288a9c85d8ba397419e1d20f58883f83020))
* version bump for gist sdk to resolve messaging not showing bug ([05dad42](https://github.com/customerio/customerio-android/commit/05dad421fc938431e459daadd6a83b6cc3b9d33e))

## [3.0.0-beta.2](https://github.com/customerio/customerio-android/compare/3.0.0-beta.1...3.0.0-beta.2) (2022-10-04)


### Bug Fixes

* version bump for gist sdk to resolve messaging not showing bug ([05dad42](https://github.com/customerio/customerio-android/commit/05dad421fc938431e459daadd6a83b6cc3b9d33e))

## [3.0.0-beta.1](https://github.com/customerio/customerio-android/compare/2.1.1...3.0.0-beta.1) (2022-09-01)


### ⚠ BREAKING CHANGES

* android 12 deep link fix

### Features

* added option to customize push notification from app ([68010f8](https://github.com/customerio/customerio-android/commit/68010f84e39872dc5b0d8cfffda8f169efeaa472))
* changes for react native package ([2f20ac3](https://github.com/customerio/customerio-android/commit/2f20ac3dd1c1ba6be215d5206b22c143e37efe94))
* in app sdk ([1036c80](https://github.com/customerio/customerio-android/commit/1036c8030259eaef1472e8c004636aee02d1af8a))
* updated client to support react native user agent ([7588526](https://github.com/customerio/customerio-android/commit/7588526bef0e7bfc130b1b5a2cc8fd915bff3483))


### Bug Fixes

* android 12 deep link fix ([fd7ae28](https://github.com/customerio/customerio-android/commit/fd7ae288a9c85d8ba397419e1d20f58883f83020))

## [3.0.0-alpha.2](https://github.com/customerio/customerio-android/compare/3.0.0-alpha.1...3.0.0-alpha.2) (2022-08-26)


### Features

* added option to customize push notification from app ([68010f8](https://github.com/customerio/customerio-android/commit/68010f84e39872dc5b0d8cfffda8f169efeaa472))
* updated client to support react native user agent ([7588526](https://github.com/customerio/customerio-android/commit/7588526bef0e7bfc130b1b5a2cc8fd915bff3483))

## [3.0.0-alpha.1](https://github.com/customerio/customerio-android/compare/2.2.0-alpha.1...3.0.0-alpha.1) (2022-08-12)


### ⚠ BREAKING CHANGES

* android 12 deep link fix

### Features

* changes for react native package ([2f20ac3](https://github.com/customerio/customerio-android/commit/2f20ac3dd1c1ba6be215d5206b22c143e37efe94))


### Bug Fixes

* android 12 deep link fix ([fd7ae28](https://github.com/customerio/customerio-android/commit/fd7ae288a9c85d8ba397419e1d20f58883f83020))

## [2.2.0-alpha.1](https://github.com/customerio/customerio-android/compare/2.1.1...2.2.0-alpha.1) (2022-08-03)


### Features

* in app sdk ([1036c80](https://github.com/customerio/customerio-android/commit/1036c8030259eaef1472e8c004636aee02d1af8a))

### [2.1.1](https://github.com/customerio/customerio-android/compare/2.1.0...2.1.1) (2022-08-01)


### Bug Fixes

* parsing exception for expired tasks ([440bb13](https://github.com/customerio/customerio-android/commit/440bb134a4a234f2bc15354dc0ad6ca90a1b5da8))

## [2.1.0](https://github.com/customerio/customerio-android/compare/2.0.1...2.1.0) (2022-08-01)


### Features

* delete expired background queue tasks ([8dca8b7](https://github.com/customerio/customerio-android/commit/8dca8b719f634d06c86628f154b2ff45d1bd6c79))


### Bug Fixes

* deploy code script commands ([#124](https://github.com/customerio/customerio-android/issues/124)) ([fe817d1](https://github.com/customerio/customerio-android/commit/fe817d10ee00ec5694d3b3e2caa8658c7cc9a1b1))

## [2.1.0-beta.1](https://github.com/customerio/customerio-android/compare/2.0.1...2.1.0-beta.1) (2022-08-01)


### Features

* delete expired background queue tasks ([8dca8b7](https://github.com/customerio/customerio-android/commit/8dca8b719f634d06c86628f154b2ff45d1bd6c79))


### Bug Fixes

* deploy code script commands ([#124](https://github.com/customerio/customerio-android/issues/124)) ([fe817d1](https://github.com/customerio/customerio-android/commit/fe817d10ee00ec5694d3b3e2caa8658c7cc9a1b1))

## [2.1.0-alpha.2](https://github.com/customerio/customerio-android/compare/2.1.0-alpha.1...2.1.0-alpha.2) (2022-07-25)


### Bug Fixes

* deploy code script commands ([#124](https://github.com/customerio/customerio-android/issues/124)) ([fe817d1](https://github.com/customerio/customerio-android/commit/fe817d10ee00ec5694d3b3e2caa8658c7cc9a1b1))

## [2.1.0-alpha.1](https://github.com/customerio/customerio-android/compare/2.0.1...2.1.0-alpha.1) (2022-07-25)


### Features

* delete expired background queue tasks ([8dca8b7](https://github.com/customerio/customerio-android/commit/8dca8b719f634d06c86628f154b2ff45d1bd6c79))

### [2.0.1](https://github.com/customerio/customerio-android/compare/2.0.0...2.0.1) (2022-06-13)


### Bug Fixes

* track opens fcm notification payload ([ab3cd18](https://github.com/customerio/customerio-android/commit/ab3cd18416463935ad6c09c869bc1e895d08a4b2))

## [2.0.0](https://github.com/customerio/customerio-android/compare/1.0.5...2.0.0) (2022-06-01)


### ⚠ BREAKING CHANGES

* get current FCM token on SDK startup
* register device attributes when set
* create background queue to make API synchronous

### Features

* add device and profile attribute setters ([789f09f](https://github.com/customerio/customerio-android/commit/789f09f0c58e21cb3200ad933a269c40e9f530dc))
* add device_manufacturer device attribute ([6a60f39](https://github.com/customerio/customerio-android/commit/6a60f3900c08e9e2bccc2fc76986dc4b66123c95))
* added support for custom track url ([b61a64b](https://github.com/customerio/customerio-android/commit/b61a64b1f17c6b7d98ec1a743ffd07bef6819a4f))
* added support for device attributes ([#71](https://github.com/customerio/customerio-android/issues/71)) ([5fedf26](https://github.com/customerio/customerio-android/commit/5fedf26f251259601ab89c15b5cd691361c30e9d))
* create background queue to make API synchronous ([2524460](https://github.com/customerio/customerio-android/commit/2524460e84531150b61647acc0d5156cd9a4b3b9))
* get current FCM token on SDK startup ([dda443d](https://github.com/customerio/customerio-android/commit/dda443d5f5d1f92edafe7e2f19a3e948d4d5d8f5))
* register device attributes when set ([0f5159e](https://github.com/customerio/customerio-android/commit/0f5159ef5848b6bcd7641351bae90e2938ee5677))
* set log level via SDK config ([81eea4e](https://github.com/customerio/customerio-android/commit/81eea4e00c518499f0de8052ec31aa87dd0ee31c))
* support for custom device attributes and config ([#77](https://github.com/customerio/customerio-android/issues/77)) ([a7dbaba](https://github.com/customerio/customerio-android/commit/a7dbaba4d82e5e7378f590425ee8b911072036a8))


### Bug Fixes

* added java compatibility in public constructors and methods ([acdec46](https://github.com/customerio/customerio-android/commit/acdec46cece2e3dc44186bc7ff3c690bf48830dc))
* added support for big decimal ([#56](https://github.com/customerio/customerio-android/issues/56)) ([58c791b](https://github.com/customerio/customerio-android/commit/58c791b8a6c9df91506cf75064622a399ca9a8d0))
* code cleanup ([c651ee6](https://github.com/customerio/customerio-android/commit/c651ee62e1a7698e4efad0376aab5710655a2e2f))
* delete device token when clear identify ([72f9753](https://github.com/customerio/customerio-android/commit/72f9753a721ea9de80cb63edf036f1daaf66f9ca))
* events are tracked to identified customer ([71634ed](https://github.com/customerio/customerio-android/commit/71634edf4c36538d459f8140c0c05a426b4f21b2))
* queue attempts to run all tasks on each run ([e180dea](https://github.com/customerio/customerio-android/commit/e180dea2e4fd8c334b0fe24185c179f42d6e8027))
* register device tokens http request ([b1c6872](https://github.com/customerio/customerio-android/commit/b1c68724ccb5218bfcd3e8b25743e309ddf83b26))

## [2.0.0-beta.1](https://github.com/customerio/customerio-android/compare/1.0.5...2.0.0-beta.1) (2022-05-13)


### ⚠ BREAKING CHANGES

* get current FCM token on SDK startup
* register device attributes when set
* create background queue to make API synchronous

### Features

* add device and profile attribute setters ([789f09f](https://github.com/customerio/customerio-android/commit/789f09f0c58e21cb3200ad933a269c40e9f530dc))
* add device_manufacturer device attribute ([6a60f39](https://github.com/customerio/customerio-android/commit/6a60f3900c08e9e2bccc2fc76986dc4b66123c95))
* added support for custom track url ([b61a64b](https://github.com/customerio/customerio-android/commit/b61a64b1f17c6b7d98ec1a743ffd07bef6819a4f))
* added support for device attributes ([#71](https://github.com/customerio/customerio-android/issues/71)) ([5fedf26](https://github.com/customerio/customerio-android/commit/5fedf26f251259601ab89c15b5cd691361c30e9d))
* create background queue to make API synchronous ([2524460](https://github.com/customerio/customerio-android/commit/2524460e84531150b61647acc0d5156cd9a4b3b9))
* get current FCM token on SDK startup ([dda443d](https://github.com/customerio/customerio-android/commit/dda443d5f5d1f92edafe7e2f19a3e948d4d5d8f5))
* register device attributes when set ([0f5159e](https://github.com/customerio/customerio-android/commit/0f5159ef5848b6bcd7641351bae90e2938ee5677))
* set log level via SDK config ([81eea4e](https://github.com/customerio/customerio-android/commit/81eea4e00c518499f0de8052ec31aa87dd0ee31c))
* support for custom device attributes and config ([#77](https://github.com/customerio/customerio-android/issues/77)) ([a7dbaba](https://github.com/customerio/customerio-android/commit/a7dbaba4d82e5e7378f590425ee8b911072036a8))


### Bug Fixes

* added java compatibility in public constructors and methods ([acdec46](https://github.com/customerio/customerio-android/commit/acdec46cece2e3dc44186bc7ff3c690bf48830dc))
* added support for big decimal ([#56](https://github.com/customerio/customerio-android/issues/56)) ([58c791b](https://github.com/customerio/customerio-android/commit/58c791b8a6c9df91506cf75064622a399ca9a8d0))
* code cleanup ([c651ee6](https://github.com/customerio/customerio-android/commit/c651ee62e1a7698e4efad0376aab5710655a2e2f))
* delete device token when clear identify ([72f9753](https://github.com/customerio/customerio-android/commit/72f9753a721ea9de80cb63edf036f1daaf66f9ca))
* events are tracked to identified customer ([71634ed](https://github.com/customerio/customerio-android/commit/71634edf4c36538d459f8140c0c05a426b4f21b2))
* queue attempts to run all tasks on each run ([e180dea](https://github.com/customerio/customerio-android/commit/e180dea2e4fd8c334b0fe24185c179f42d6e8027))
* register device tokens http request ([b1c6872](https://github.com/customerio/customerio-android/commit/b1c68724ccb5218bfcd3e8b25743e309ddf83b26))

## [2.0.0-alpha.4](https://github.com/customerio/customerio-android/compare/2.0.0-alpha.3...2.0.0-alpha.4) (2022-05-13)


### Bug Fixes

* added alternative route to fetch screen name for automatic tracking ([#92](https://github.com/customerio/customerio-android/issues/92)) ([37a20b5](https://github.com/customerio/customerio-android/commit/37a20b59da8778e9eba3baf067c9276ca878806d))
* delete device token when clear identify ([72f9753](https://github.com/customerio/customerio-android/commit/72f9753a721ea9de80cb63edf036f1daaf66f9ca))

## [2.0.0-alpha.3](https://github.com/customerio/customerio-android/compare/2.0.0-alpha.2...2.0.0-alpha.3) (2022-05-11)


### ⚠ BREAKING CHANGES

* get current FCM token on SDK startup
* register device attributes when set

### Features

* add device and profile attribute setters ([789f09f](https://github.com/customerio/customerio-android/commit/789f09f0c58e21cb3200ad933a269c40e9f530dc))
* added support for custom track url ([b61a64b](https://github.com/customerio/customerio-android/commit/b61a64b1f17c6b7d98ec1a743ffd07bef6819a4f))
* get current FCM token on SDK startup ([dda443d](https://github.com/customerio/customerio-android/commit/dda443d5f5d1f92edafe7e2f19a3e948d4d5d8f5))
* register device attributes when set ([0f5159e](https://github.com/customerio/customerio-android/commit/0f5159ef5848b6bcd7641351bae90e2938ee5677))
* set log level via SDK config ([81eea4e](https://github.com/customerio/customerio-android/commit/81eea4e00c518499f0de8052ec31aa87dd0ee31c))


### Bug Fixes

* queue attempts to run all tasks on each run ([e180dea](https://github.com/customerio/customerio-android/commit/e180dea2e4fd8c334b0fe24185c179f42d6e8027))

## [2.0.0-alpha.2](https://github.com/customerio/customerio-android/compare/2.0.0-alpha.1...2.0.0-alpha.2) (2022-05-02)


### Bug Fixes

* events are tracked to identified customer ([71634ed](https://github.com/customerio/customerio-android/commit/71634edf4c36538d459f8140c0c05a426b4f21b2))
* register device tokens http request ([b1c6872](https://github.com/customerio/customerio-android/commit/b1c68724ccb5218bfcd3e8b25743e309ddf83b26))

## [1.0.5](https://github.com/customerio/customerio-android/compare/1.0.4...1.0.5) (2022-04-30)


### Bug Fixes

* added alternative route to fetch screen name for automatic tracking ([#92](https://github.com/customerio/customerio-android/issues/92)) ([37a20b5](https://github.com/customerio/customerio-android/commit/37a20b59da8778e9eba3baf067c9276ca878806d))

## [2.0.0-alpha.1](https://github.com/customerio/customerio-android/compare/1.1.0-alpha.3...2.0.0-alpha.1) (2022-04-20)


### ⚠ BREAKING CHANGES

* create background queue to make API synchronous

### Features

* create background queue to make API synchronous ([2524460](https://github.com/customerio/customerio-android/commit/2524460e84531150b61647acc0d5156cd9a4b3b9))

## [1.1.0-alpha.3](https://github.com/customerio/customerio-android/compare/1.1.0-alpha.2...1.1.0-alpha.3) (2022-04-19)


### Features

* add device_manufacturer device attribute ([6a60f39](https://github.com/customerio/customerio-android/commit/6a60f3900c08e9e2bccc2fc76986dc4b66123c95))

## [1.1.0-alpha.2](https://github.com/customerio/customerio-android/compare/1.1.0-alpha.1...1.1.0-alpha.2) (2022-03-25)


### Features

* support for custom device attributes and config ([#77](https://github.com/customerio/customerio-android/issues/77)) ([a7dbaba](https://github.com/customerio/customerio-android/commit/a7dbaba4d82e5e7378f590425ee8b911072036a8))


### Bug Fixes

* added java compatibility in public constructors and methods ([2bb73be](https://github.com/customerio/customerio-android/commit/2bb73be50dd66ef7e308d587b19daaf66c7e0968))
* allow http requests in host app ([#74](https://github.com/customerio/customerio-android/issues/74)) ([1035648](https://github.com/customerio/customerio-android/commit/103564882df611e956a54cba0c2635acd3f1997a))
* code cleanup ([c651ee6](https://github.com/customerio/customerio-android/commit/c651ee62e1a7698e4efad0376aab5710655a2e2f))
* crash using invalid characters in HTTP header ([#75](https://github.com/customerio/customerio-android/issues/75)) ([81a065e](https://github.com/customerio/customerio-android/commit/81a065ee44e1df58b3009287966e913f2888a6e1))

## [1.1.0-alpha.1](https://github.com/customerio/customerio-android/compare/1.0.1...1.1.0-alpha.1) (2022-03-21)


### Features

* added support for device attributes ([#71](https://github.com/customerio/customerio-android/issues/71)) ([5fedf26](https://github.com/customerio/customerio-android/commit/5fedf26f251259601ab89c15b5cd691361c30e9d))
* support for custom device attributes and config ([#77](https://github.com/customerio/customerio-android/issues/77)) ([a7dbaba](https://github.com/customerio/customerio-android/commit/a7dbaba4d82e5e7378f590425ee8b911072036a8))


### Bug Fixes

* added java compatibility in public constructors and methods ([acdec46](https://github.com/customerio/customerio-android/commit/acdec46cece2e3dc44186bc7ff3c690bf48830dc))
* added support for big decimal ([#56](https://github.com/customerio/customerio-android/issues/56)) ([58c791b](https://github.com/customerio/customerio-android/commit/58c791b8a6c9df91506cf75064622a399ca9a8d0))

## [1.0.4](https://github.com/customerio/customerio-android/compare/1.0.3...1.0.4) (2022-03-21)


### Bug Fixes

* crash using invalid characters in HTTP header ([#75](https://github.com/customerio/customerio-android/issues/75)) ([81a065e](https://github.com/customerio/customerio-android/commit/81a065ee44e1df58b3009287966e913f2888a6e1))

## [1.0.3](https://github.com/customerio/customerio-android/compare/1.0.2...1.0.3) (2022-03-15)


### Bug Fixes

* allow http requests in host app ([#74](https://github.com/customerio/customerio-android/issues/74)) ([1035648](https://github.com/customerio/customerio-android/commit/103564882df611e956a54cba0c2635acd3f1997a))

## [1.0.2](https://github.com/customerio/customerio-android/compare/1.0.1...1.0.2) (2022-03-11)


### Bug Fixes

* added java compatibility in public constructors and methods ([2bb73be](https://github.com/customerio/customerio-android/commit/2bb73be50dd66ef7e308d587b19daaf66c7e0968))

## [1.0.1](https://github.com/customerio/customerio-android/compare/1.0.0...1.0.1) (2022-01-25)


### Bug Fixes

* number parsing coverts to double ([#57](https://github.com/customerio/customerio-android/issues/57)) ([72a80fe](https://github.com/customerio/customerio-android/commit/72a80fe38932a199dda68b3fcbfd10b3d025f450))

## [1.0.1-beta.1](https://github.com/customerio/customerio-android/compare/1.0.0...1.0.1-beta.1) (2022-01-21)


### Bug Fixes

* number parsing coverts to double ([#57](https://github.com/customerio/customerio-android/issues/57)) ([72a80fe](https://github.com/customerio/customerio-android/commit/72a80fe38932a199dda68b3fcbfd10b3d025f450))

# 1.0.0 (2022-01-19)


### Bug Fixes

* pro-guarding removal of enums  ([#39](https://github.com/customerio/customerio-android/issues/39)) ([dd27d35](https://github.com/customerio/customerio-android/commit/dd27d3567172b5c8b1e0bdd989126afa8f290541))
* timestamp conversion issue ([#37](https://github.com/customerio/customerio-android/issues/37)) ([d986f54](https://github.com/customerio/customerio-android/commit/d986f546c9200fd5477de719db2e6b804fe9308f))
* updated host endpoints ([#53](https://github.com/customerio/customerio-android/issues/53)) ([efe2b9e](https://github.com/customerio/customerio-android/commit/efe2b9e36e0fe86abd9666f2d3af4fb84eadcdfb))
* user agent ([#42](https://github.com/customerio/customerio-android/issues/42)) ([37af83e](https://github.com/customerio/customerio-android/commit/37af83ed0f73d4123354f2cf8d68befbd172c8f8))


### Features

* automatic screen tracking ([#52](https://github.com/customerio/customerio-android/issues/52)) ([8f95ebb](https://github.com/customerio/customerio-android/commit/8f95ebb59a1dbb94060a44a3db21b8bd2bac5723))
* event tracking, identify customer ([#29](https://github.com/customerio/customerio-android/issues/29)) ([5181268](https://github.com/customerio/customerio-android/commit/51812682a79154b9fdc38ac604fb0cc2c4c74156))
* manual screen tracking ([#51](https://github.com/customerio/customerio-android/issues/51)) ([213a278](https://github.com/customerio/customerio-android/commit/213a278e668f0e26ffef5983d6357b5370f96ad2))
* Push notification  ([#31](https://github.com/customerio/customerio-android/issues/31)) ([c639802](https://github.com/customerio/customerio-android/commit/c639802a44ea442212c65155efeb470f7c6ac64e))
* Rich push support  ([#34](https://github.com/customerio/customerio-android/issues/34)) ([2480127](https://github.com/customerio/customerio-android/commit/2480127f976f01f91c3d75f55ffc2589a2b58f59))

# [1.0.0-beta.2](https://github.com/customerio/customerio-android/compare/1.0.0-beta.1...1.0.0-beta.2) (2022-01-18)


### Bug Fixes

* updated host endpoints ([#53](https://github.com/customerio/customerio-android/issues/53)) ([efe2b9e](https://github.com/customerio/customerio-android/commit/efe2b9e36e0fe86abd9666f2d3af4fb84eadcdfb))

# [1.0.0-alpha.9](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.8...1.0.0-alpha.9) (2022-01-14)


### Bug Fixes

* updated host endpoints ([#53](https://github.com/customerio/customerio-android/issues/53)) ([efe2b9e](https://github.com/customerio/customerio-android/commit/efe2b9e36e0fe86abd9666f2d3af4fb84eadcdfb))

# [1.0.0-alpha.8](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.7...1.0.0-alpha.8) (2021-12-15)


### Features

* automatic screen tracking ([#52](https://github.com/customerio/customerio-android/issues/52)) ([8f95ebb](https://github.com/customerio/customerio-android/commit/8f95ebb59a1dbb94060a44a3db21b8bd2bac5723))

# 1.0.0-beta.1 (2021-12-16)


### Bug Fixes

* pro-guarding removal of enums  ([#39](https://github.com/customerio/customerio-android/issues/39)) ([dd27d35](https://github.com/customerio/customerio-android/commit/dd27d3567172b5c8b1e0bdd989126afa8f290541))
* timestamp conversion issue ([#37](https://github.com/customerio/customerio-android/issues/37)) ([d986f54](https://github.com/customerio/customerio-android/commit/d986f546c9200fd5477de719db2e6b804fe9308f))
* user agent ([#42](https://github.com/customerio/customerio-android/issues/42)) ([37af83e](https://github.com/customerio/customerio-android/commit/37af83ed0f73d4123354f2cf8d68befbd172c8f8))


### Features

* automatic screen tracking ([#52](https://github.com/customerio/customerio-android/issues/52)) ([8f95ebb](https://github.com/customerio/customerio-android/commit/8f95ebb59a1dbb94060a44a3db21b8bd2bac5723))
* event tracking, identify customer ([#29](https://github.com/customerio/customerio-android/issues/29)) ([5181268](https://github.com/customerio/customerio-android/commit/51812682a79154b9fdc38ac604fb0cc2c4c74156))
* manual screen tracking ([#51](https://github.com/customerio/customerio-android/issues/51)) ([213a278](https://github.com/customerio/customerio-android/commit/213a278e668f0e26ffef5983d6357b5370f96ad2))
* Push notification  ([#31](https://github.com/customerio/customerio-android/issues/31)) ([c639802](https://github.com/customerio/customerio-android/commit/c639802a44ea442212c65155efeb470f7c6ac64e))
* Rich push support  ([#34](https://github.com/customerio/customerio-android/issues/34)) ([2480127](https://github.com/customerio/customerio-android/commit/2480127f976f01f91c3d75f55ffc2589a2b58f59))

# [1.0.0-alpha.7](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.6...1.0.0-alpha.7) (2021-12-14)


### Features

* manual screen tracking ([#51](https://github.com/customerio/customerio-android/issues/51)) ([213a278](https://github.com/customerio/customerio-android/commit/213a278e668f0e26ffef5983d6357b5370f96ad2))

# [1.0.0-alpha.6](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.5...1.0.0-alpha.6) (2021-11-23)


### Bug Fixes

* user agent ([#42](https://github.com/customerio/customerio-android/issues/42)) ([37af83e](https://github.com/customerio/customerio-android/commit/37af83ed0f73d4123354f2cf8d68befbd172c8f8))

# [1.0.0-alpha.5](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.4...1.0.0-alpha.5) (2021-11-18)


### Bug Fixes

* pro-guarding removal of enums  ([#39](https://github.com/customerio/customerio-android/issues/39)) ([dd27d35](https://github.com/customerio/customerio-android/commit/dd27d3567172b5c8b1e0bdd989126afa8f290541))

# [1.0.0-alpha.4](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.3...1.0.0-alpha.4) (2021-11-17)


### Bug Fixes

* timestamp conversion issue ([#37](https://github.com/customerio/customerio-android/issues/37)) ([d986f54](https://github.com/customerio/customerio-android/commit/d986f546c9200fd5477de719db2e6b804fe9308f))

# [1.0.0-alpha.3](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.2...1.0.0-alpha.3) (2021-11-11)


### Features

* Rich push support  ([#34](https://github.com/customerio/customerio-android/issues/34)) ([2480127](https://github.com/customerio/customerio-android/commit/2480127f976f01f91c3d75f55ffc2589a2b58f59))

# [1.0.0-alpha.2](https://github.com/customerio/customerio-android/compare/1.0.0-alpha.1...1.0.0-alpha.2) (2021-11-02)


### Features

* Push notification  ([#31](https://github.com/customerio/customerio-android/issues/31)) ([c639802](https://github.com/customerio/customerio-android/commit/c639802a44ea442212c65155efeb470f7c6ac64e))

# 1.0.0-alpha.1 (2021-10-15)


### Features

* event tracking, identify customer ([#29](https://github.com/customerio/customerio-android/issues/29)) ([5181268](https://github.com/customerio/customerio-android/commit/51812682a79154b9fdc38ac604fb0cc2c4c74156))
