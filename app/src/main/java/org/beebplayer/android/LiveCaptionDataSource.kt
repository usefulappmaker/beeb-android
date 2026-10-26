// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*

@androidx.annotation.OptIn(UnstableApi::class)
class LiveCaptionDataSource(private val upstream: DataSource, private val captions: LiveCaptionResources) : DataSource {
    private var active: DataSource = upstream
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners.add(listener); upstream.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        active = if(captions.owns(dataSpec.uri.toString())) ByteArrayDataSource(try { captions.resource(dataSpec.uri.toString()) } catch(e: Exception) { throw java.io.IOException("Live caption resource unavailable", e) }).also { source -> listeners.forEach(source::addTransferListener) } else upstream
        return active.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer,offset,length)
    override fun getUri(): Uri? = active.uri
    override fun getResponseHeaders(): Map<String,List<String>> = active.responseHeaders
    override fun close() = active.close()
}
