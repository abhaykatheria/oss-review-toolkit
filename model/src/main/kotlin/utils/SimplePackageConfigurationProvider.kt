/*
 * Copyright (C) 2017-2020 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.model.utils

import com.here.ort.model.Identifier
import com.here.ort.model.Provenance
import com.here.ort.model.config.PackageConfiguration
import com.here.ort.model.readValue
import java.io.File
import java.io.FileFilter
import java.util.LinkedList

class SimplePackageConfigurationProvider(
    configurations: List<PackageConfiguration> = emptyList()
) : PackageConfigurationProvider {
    companion object {
        fun fromDirectory(directory: File): SimplePackageConfigurationProvider {
            val entries = directory.getChildrenRec().map { it.readValue<PackageConfiguration>() }
            return SimplePackageConfigurationProvider(entries)
        }

        private fun File.getChildrenRec(): List<File> {
            val result = mutableListOf<File>()
            if (!isDirectory) return result

            val queue = LinkedList<File>()
            queue.add(this)

            while (queue.isNotEmpty()) {
                val directory = queue.remove()
                directory.listFiles(FileFilter { !it.isHidden }).forEach { file ->
                    if (file.isDirectory) queue.add(file)
                    else result.add(file)
                }
            }

            return result
        }
    }

    private val configurationsById: Map<Identifier, List<PackageConfiguration>>

    init {
        configurationsById = configurations.groupByTo(HashMap()) { it.id }
    }

    override fun getPackageConfiguration(packageId: Identifier, provenance: Provenance): PackageConfiguration? =
        configurationsById[packageId].orEmpty().find { it.matches(packageId, provenance) }
}
