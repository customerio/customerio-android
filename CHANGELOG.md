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
