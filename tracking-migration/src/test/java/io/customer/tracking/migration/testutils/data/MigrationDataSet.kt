package io.customer.tracking.migration.testutils.data

import org.json.JSONObject

/**
 * Migration module heavily relies on JSON data for processing. It is important to
 * verify that the JSON data is correctly parsed and processed.
 * The file parses the JSON data and provides the expected data for testing so that
 * the JSON data can be verified against the expected data.
 */

interface MigrationDataSet {
    val rawJson: String
    fun encodedJson(): JSONObject = JSONObject(rawJson)
}

sealed interface IdentifyProfileQueueTaskData : MigrationDataSet {
    object EmptyProperties : IdentifyProfileQueueTaskData {
        override val rawJson: String = """
            {
              "storageId": "a1bae0ff-6a96-4095-9e02-b65188afe72c",
              "type": "IdentifyProfile",
              "data": "{\"identifier\":\"oltrfbwtmg\",\"attributes\":{}}",
              "runResults": {
                "totalRuns": 10
              }
            }
        """.trimIndent()
    }

    object CustomProperties : IdentifyProfileQueueTaskData {
        override val rawJson: String = """
            {
              "storageId": "11986cb7-200a-40be-96b6-0f3650e48314",
              "type": "IdentifyProfile",
              "data": "{\"identifier\":\"ycfwlrhfhc\",\"attributes\":{\"brand\":\"local\",\"price\":135,\"imported\":false,\"sizes\":[\"Small\",\"Medium\",\"Large\"],\"attributes\":{\"sale\":true,\"discount\":0.15,\"tags\":[\"sale\",\"discount\"],\"variations\":[{\"color\":\"red\",\"price\":100,\"tags\":[\"popular\",\"new\"]},{\"color\":\"blue\",\"price\":120}]}}}",
              "runResults": {
                "totalRuns": 0
              }
            }
        """.trimIndent()
    }
}

sealed interface TrackEventQueueTaskData : MigrationDataSet {
    sealed interface Event : TrackEventQueueTaskData {
        object EmptyProperties : Event {
            override val rawJson: String = """
                {
                  "storageId": "115d7429-9f39-4d5d-a784-581170869e36",
                  "type": "TrackEvent",
                  "data": "{\"identifier\":\"kplclgjuco\",\"event\":{\"name\":\"grcraqaelr\",\"type\":\"event\",\"data\":{},\"timestamp\":1721299502}}",
                  "runResults": {
                    "totalRuns": 2
                  }
                }
            """.trimIndent()
        }

        object CustomProperties : Event {
            override val rawJson: String = """
                {
                  "storageId": "8ee064df-f8a9-40b1-991c-0fb32a6bc353",
                  "type": "TrackEvent",
                  "data": "{\"identifier\":\"fzhoazuhjk\",\"event\":{\"name\":\"cyopvewbbz\",\"type\":\"event\",\"data\":{\"brand\":\"local\",\"price\":135,\"imported\":false,\"sizes\":[\"Small\",\"Medium\",\"Large\"],\"attributes\":{\"sale\":true,\"discount\":0.15,\"tags\":[\"sale\",\"discount\"],\"variations\":[{\"color\":\"red\",\"price\":100,\"tags\":[\"popular\",\"new\"]},{\"color\":\"blue\",\"price\":120}]}},\"timestamp\":1721299502}}",
                  "runResults": {
                    "totalRuns": 0
                  }
                }
            """.trimIndent()
        }

        object NullTimestamp : Event {
            override val rawJson: String = """
                {
                  "storageId": "58093800-fa4f-483f-97fe-091c922b8c8d",
                  "type": "TrackEvent",
                  "data": "{\"identifier\":\"cicslibnal\",\"event\":{\"name\":\"ldwhusliak\",\"type\":\"event\",\"data\":{}}}",
                  "runResults": {
                    "totalRuns": 9
                  }
                }
            """.trimIndent()
        }
    }

    sealed interface Screen : TrackEventQueueTaskData {
        object EmptyProperties : Screen {
            override val rawJson: String = """
                {
                  "storageId": "36dd0747-f68a-48d5-bdb2-6eecd927a071",
                  "type": "TrackEvent",
                  "data": "{\"identifier\":\"ypsfnkyinl\",\"event\":{\"name\":\"bgibutjygk\",\"type\":\"screen\",\"data\":{},\"timestamp\":1721299502}}",
                  "runResults": {
                    "totalRuns": 8
                  }
                }
            """.trimIndent()
        }

        object CustomProperties : Screen {
            override val rawJson: String = """
                {
                  "storageId": "5f85b3bf-ee21-40db-abbf-0d3f4970b342",
                  "type": "TrackEvent",
                  "data": "{\"identifier\":\"zhfgxqmkiw\",\"event\":{\"name\":\"luwnomoscm\",\"type\":\"screen\",\"data\":{\"brand\":\"local\",\"price\":135,\"imported\":false,\"sizes\":[\"Small\",\"Medium\",\"Large\"],\"attributes\":{\"sale\":true,\"discount\":0.15,\"tags\":[\"sale\",\"discount\"],\"variations\":[{\"color\":\"red\",\"price\":100,\"tags\":[\"popular\",\"new\"]},{\"color\":\"blue\",\"price\":120}]}},\"timestamp\":1721299502}}",
                  "runResults": {
                    "totalRuns": 7
                  }
                }
            """.trimIndent()
        }

        object NullTimestamp : Screen {
            override val rawJson: String = """
                {
                  "storageId": "2ab0060c-e48b-4dd6-8c59-adf3c15eb98c",
                  "type": "TrackEvent",
                  "data": "{\"identifier\":\"qlivayebdk\",\"event\":{\"name\":\"yeblfuxpmx\",\"type\":\"screen\",\"data\":{}}}",
                  "runResults": {
                    "totalRuns": 1
                  }
                }
            """.trimIndent()
        }
    }
}

sealed interface PushMetric : MigrationDataSet {
    object Opened : PushMetric {
        override val rawJson: String = """
            {
              "storageId": "72be183f-e9e2-4292-982f-e540967c7e68",
              "type": "TrackPushMetric",
              "data": "{\"delivery_id\":\"ibavwuplvy\",\"device_id\":\"fbytgkpavd\",\"event\":\"opened\",\"timestamp\":1721299502}",
              "runResults": {
                "totalRuns": 9
              }
            }
        """.trimIndent()
    }
}

sealed interface InAppDelivery : MigrationDataSet {
    object EmptyMetadata : InAppDelivery {
        override val rawJson: String = """
            {
              "storageId": "7c0efe27-2c0d-4562-9c20-d932ff9b6de5",
              "type": "TrackDeliveryEvent",
              "data": "{\"type\":\"in_app\",\"payload\":{\"delivery_id\":\"hzbuzikuxq\",\"event\":\"delivered\",\"timestamp\":1721299502,\"metadata\":{}}}",
              "runResults": {
                "totalRuns": 5
              }
            }
        """.trimIndent()
    }

    object Clicked : InAppDelivery {
        override val rawJson: String = """
            {
              "storageId": "b6c03909-b81a-433f-8d82-81bc0a28f9af",
              "type": "TrackDeliveryEvent",
              "data": "{\"type\":\"in_app\",\"payload\":{\"delivery_id\":\"mvumcfruyg\",\"event\":\"clicked\",\"timestamp\":1721299502,\"metadata\":{\"color\":\"green\",\"price\":\"135\",\"imported\":\"false\",\"sizes\":\"['S', 'M', 'L']\"}}}",
              "runResults": {
                "totalRuns": 9
              }
            }
        """.trimIndent()
    }
}

sealed interface RegisterPushNotificationQueueTaskData : MigrationDataSet {
    object CustomProperties : RegisterPushNotificationQueueTaskData {
        override val rawJson: String = """
            {
              "storageId": "80a28969-51b2-4546-a9ad-3ef488a66403",
              "type": "RegisterDeviceToken",
              "data": "{\"profileIdentified\":\"profileIdentified\",\"device\":{\"id\":\"fnvmsvmdjj\",\"platform\":\"android\",\"last_used\":1721299502,\"attributes\":{\"brand\":\"local\",\"price\":135,\"imported\":false,\"sizes\":[\"Small\",\"Medium\",\"Large\"],\"attributes\":{\"sale\":true,\"discount\":0.15,\"tags\":[\"sale\",\"discount\"],\"variations\":[{\"color\":\"red\",\"price\":100,\"tags\":[\"popular\",\"new\"]},{\"color\":\"blue\",\"price\":120}]}}}}",
              "runResults": {
                "totalRuns": 8
              }
            }
        """.trimIndent()
    }

    object InvalidLastUsed : RegisterPushNotificationQueueTaskData {
        override val rawJson: String = """
            {
              "storageId": "ef2724bf-d7ff-455e-aeca-2a7caec61c06",
              "type": "RegisterDeviceToken",
              "data": "{\"profileIdentified\":\"profileIdentified\",\"device\":{\"id\":\"gutkexyugs\",\"platform\":\"android\",\"lastUsed\":1721299502,\"attributes\":{\"brand\":\"local\",\"price\":135,\"imported\":false,\"sizes\":[\"Small\",\"Medium\",\"Large\"],\"attributes\":{\"sale\":true,\"discount\":0.15,\"tags\":[\"sale\",\"discount\"],\"variations\":[{\"color\":\"red\",\"price\":100,\"tags\":[\"popular\",\"new\"]},{\"color\":\"blue\",\"price\":120}]}}}}",
              "runResults": {
                "totalRuns": 3
              }
            }
        """.trimIndent()
    }

    object NullLastUsed : RegisterPushNotificationQueueTaskData {
        override val rawJson: String = """
            {
              "storageId": "6dbbb975-0712-43eb-97d0-b321154ec238",
              "type": "RegisterDeviceToken",
              "data": "{\"profileIdentified\":\"profileIdentified\",\"device\":{\"id\":\"mwyclthxgg\",\"platform\":\"android\",\"attributes\":{\"brand\":\"local\",\"price\":135,\"imported\":false,\"sizes\":[\"Small\",\"Medium\",\"Large\"],\"attributes\":{\"sale\":true,\"discount\":0.15,\"tags\":[\"sale\",\"discount\"],\"variations\":[{\"color\":\"red\",\"price\":100,\"tags\":[\"popular\",\"new\"]},{\"color\":\"blue\",\"price\":120}]}}}}",
              "runResults": {
                "totalRuns": 1
              }
            }
        """.trimIndent()
    }
}

sealed interface DeletePushNotificationQueueTaskData : MigrationDataSet {
    object ValidToken : DeletePushNotificationQueueTaskData {
        override val rawJson: String = """
        {
          "storageId": "2137e81e-cbe3-4216-9d8b-1ccb11c18bc0",
          "type": "DeletePushToken",
          "data": "{\"profileIdentified\":\"profileIdentified\",\"deviceToken\":\"nakfrccqko\"}",
          "runResults": {
            "totalRuns": 8
          }
        }
        """.trimIndent()
    }
}
