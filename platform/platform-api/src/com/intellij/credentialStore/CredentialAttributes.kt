/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.credentialStore

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ArrayUtil
import com.intellij.util.ExceptionUtil
import com.intellij.util.nullize
import com.intellij.util.text.CharArrayCharSequence
import org.jetbrains.io.toByteArray
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference

/**
 * requestor is deprecated. Never use it in new code.
 */
data class CredentialAttributes(val serviceName: String, val userName: String? = null, val requestor: Class<*>? = null) {
}

// user cannot be empty, but password can be
class Credentials(user: String?, val password: OneTimeString? = null) {
  constructor(user: String?, password: String?) : this(user, password?.let(::OneTimeString))

  constructor(user: String?, password: CharArray?) : this(user, password?.let { OneTimeString(it) })

  val userName = user.nullize()

  @JvmOverloads
  fun getPasswordAsString(clear: Boolean = true) = password?.toString(clear)

  override fun equals(other: Any?): Boolean {
    if (other !is Credentials) return false
    return userName == other.userName && password == other.password
  }

  override fun hashCode() = (userName?.hashCode() ?: 0) * 37 + (password?.hashCode() ?: 0)
}

fun CredentialAttributes(requestor: Class<*>, userName: String?) = CredentialAttributes(requestor.name, userName, requestor)

fun Credentials?.isFulfilled() = this != null && userName != null && password != null

fun Credentials?.isEmpty() = this == null || (userName == null && password == null)

// input will be cleared
@JvmOverloads
fun OneTimeString(value: ByteArray, offset: Int = 0, length: Int = value.size - offset): OneTimeString {
  if (length == 0) {
    return OneTimeString(ArrayUtil.EMPTY_CHAR_ARRAY)
  }

  // jdk decodes to heap array, but since this code is very critical, we cannot rely on it, so, we don't use Charsets.UTF_8.decode()
  val charsetDecoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
  val charArray = CharArray((value.size * charsetDecoder.maxCharsPerByte().toDouble()).toInt())
  charsetDecoder.reset()
  val charBuffer = CharBuffer.wrap(charArray)
  var cr = charsetDecoder.decode(ByteBuffer.wrap(value, offset, length), charBuffer, true)
  if (!cr.isUnderflow) {
    cr.throwException()
  }
  cr = charsetDecoder.flush(charBuffer)
  if (!cr.isUnderflow) {
    cr.throwException()
  }

  value.fill(0, offset, offset + length)
  return OneTimeString(charArray, 0, charBuffer.position())
}

private val oneTimeStringEnabled = com.intellij.util.SystemProperties.getBooleanProperty("one.time.string.enabled", false)

@Suppress("EqualsOrHashCode")
// todo - eliminate toString
class OneTimeString @JvmOverloads constructor(value: CharArray, offset: Int = 0, length: Int = value.size) : CharArrayCharSequence(value, offset, offset + length) {
  private val consumed = AtomicReference<String?>()

  constructor(value: String): this(value.toCharArray()) {
  }

  private fun consume(willBeCleared: Boolean) {
    if (!oneTimeStringEnabled) {
      return
    }

    if (!willBeCleared) {
      consumed.get()?.let { throw IllegalStateException("Already consumed: $it\n---\n") }
    }
    else if (!consumed.compareAndSet(null, ExceptionUtil.currentStackTrace())) {
      throw IllegalStateException("Already consumed at ${consumed.get()}")
    }
  }

  @JvmOverloads
  fun toString(clear: Boolean = true): String {
    consume(clear)
    // todo clear
    return super.toString()
  }

  // string will be cleared and not valid after
  @JvmOverloads
  fun toByteArray(clear: Boolean = true): ByteArray {
    consume(clear)

    val result = Charsets.UTF_8.encode(CharBuffer.wrap(myChars, myStart, length))
    if (clear && oneTimeStringEnabled) {
      myChars.fill('\u0000', myStart, myEnd)
    }
    return result.toByteArray()
  }

  @JvmOverloads
  fun toCharArray(clear: Boolean = true): CharArray {
    consume(clear)
    // todo clear
    return chars
  }

  override fun equals(other: Any?): Boolean {
    if (other is CharSequence) {
      return StringUtil.equals(this, other)
    }
    return super.equals(other)
  }

  fun appendTo(builder: StringBuilder) {
    consume(false)
    builder.append(myChars, myStart, length)
  }
}