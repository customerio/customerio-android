/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.messaginginapp.testutils.extension

import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.getMessage

fun Message.getNewRandom(): Message = InAppMessage(String.random, String.random).getMessage()
