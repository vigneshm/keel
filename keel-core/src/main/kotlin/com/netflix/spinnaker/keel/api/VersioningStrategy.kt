/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.netflix.frigga.ami.AppVersion
import com.netflix.rocket.semver.shaded.DebianVersionComparator
import com.netflix.spinnaker.keel.api.SortType.INCREASING
import com.netflix.spinnaker.keel.api.SortType.SEMVER
import com.netflix.spinnaker.keel.exceptions.InvalidRegexException
import com.netflix.spinnaker.keel.serialization.PropertyNamePolymorphicDeserializer
import net.swiftzer.semver.SemVer
import org.slf4j.LoggerFactory
import org.springframework.util.comparator.NullSafeComparator

/**
 * Strategy for how to sort versions of artifacts.
 */
@JsonDeserialize(using = VersioningStrategyDeserializer::class)
sealed class VersioningStrategy(
  @JsonIgnore
  open val comparator: Comparator<String>
)

@JsonDeserialize(using = JsonDeserializer.None::class)
object DebianSemVerVersioningStrategy : VersioningStrategy(DEBIAN_VERSION_COMPARATOR) {
  override fun toString(): String =
    javaClass.simpleName

  override fun equals(other: Any?): Boolean {
    return other is DebianSemVerVersioningStrategy
  }
}

@JsonDeserialize(using = JsonDeserializer.None::class)
data class DockerVersioningStrategy(
  val strategy: TagVersionStrategy,
  val captureGroupRegex: String? = null
) : VersioningStrategy(TagComparator(strategy, captureGroupRegex)) {
  override fun toString(): String =
    "${javaClass.simpleName}[strategy=$strategy, captureGroupRegex=$captureGroupRegex]}"
}

/**
 * Comparator that supports all the tag options for docker containers
 */
class TagComparator(
  private val strategy: TagVersionStrategy,
  private val customRegex: String? = null
) : Comparator<String> {

  private val semverComparator = NullSafeComparator(SEMVER_COMPARATOR, false)
  private val increasingComparator = NullSafeComparator(INCREASING_COMPARATOR, false)

  override fun compare(o1: String, o2: String): Int {
    val i1 = parseWithRegex(o1, strategy, customRegex)
    val i2 = parseWithRegex(o2, strategy, customRegex)
    return when (strategy.sortType) {
      SEMVER -> semverComparator.compare(parseSemver(i1), parseSemver(i2))
      INCREASING -> increasingComparator.compare(i1?.toIntOrNull(), i2?.toIntOrNull())
    }
  }

  companion object {
    private val log by lazy { LoggerFactory.getLogger(this::class.java) }

    fun parseWithRegex(input: String, strategy: TagVersionStrategy, customRegex: String?): String? {
      val regex = Regex(customRegex ?: strategy.regex)
      val result = regex.find(input) ?: return null
      return when (result.groupValues.size) {
        2 -> result.groupValues[1]
        1 -> {
          log.warn("Regex (${customRegex ?: strategy.regex}) produced zero capture groups on tag $input")
          null
        }
        else -> {
          throw InvalidRegexException(customRegex ?: strategy.regex, input)
        }
      }
    }
  }

  /**
   * Trims a leading "v" off of the semver if present
   */
  private fun parseSemver(input: String?): SemVer? {
    input ?: return null
    return try {
      SemVer.parse(input.removePrefix("v"))
    } catch (e: IllegalArgumentException) {
      null
    }
  }
}

val SEMVER_COMPARATOR: Comparator<SemVer> = Comparator<SemVer> { a, b ->
  b.compareTo(a)
}

val INCREASING_COMPARATOR: Comparator<Int> = Comparator<Int> { a, b ->
  b - a
}

// descending by default
val DEBIAN_VERSION_COMPARATOR: Comparator<String> = object : Comparator<String> {
  override fun compare(s1: String, s2: String) =
    debComparator.compare(s2.toVersion(), s1.toVersion())

  private val debComparator = NullSafeComparator(DebianVersionComparator(), true)

  private fun String.toVersion(): String? = run {
    val appVersion = AppVersion.parseName(this)
    if (appVersion == null) {
      log.warn("Unparseable artifact version \"{}\" encountered", this)
      null
    } else {
      removePrefix(appVersion.packageName).removePrefix("-")
    }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

class VersioningStrategyDeserializer : PropertyNamePolymorphicDeserializer<VersioningStrategy>(VersioningStrategy::class.java) {
  override fun identifySubType(fieldNames: Collection<String>): Class<out VersioningStrategy> =
    when {
      "tagVersionStrategy" in fieldNames -> DockerVersioningStrategy::class.java
      else -> DebianSemVerVersioningStrategy::class.java
    }
}
