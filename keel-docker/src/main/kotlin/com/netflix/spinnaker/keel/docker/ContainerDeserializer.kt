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
package com.netflix.spinnaker.keel.docker

import com.netflix.spinnaker.keel.serialization.PropertyNamePolymorphicDeserializer

class ContainerDeserializer :
  PropertyNamePolymorphicDeserializer<Container>(Container::class.java) {
  override fun identifySubType(fieldNames: Collection<String>): Class<out Container> =
    when {
      "digest" in fieldNames -> ContainerWithDigest::class.java
      else -> ContainerWithVersionedTag::class.java
    }
}
