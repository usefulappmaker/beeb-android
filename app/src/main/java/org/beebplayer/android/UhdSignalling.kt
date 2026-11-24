// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

object UhdSignalling {
    fun hlg(bytes: ByteArray): Boolean {
        // A complete colr box occupies twelve bytes from its type: type(4) kind(4) primaries(2) transfer(2).
        // The scan must include a box that ends exactly on the last byte of a truncated fetch.
        for(i in 4..bytes.size-12) {
            if(bytes[i]!=99.toByte() || bytes[i+1]!=111.toByte() || bytes[i+2]!=108.toByte() || bytes[i+3]!=114.toByte()) continue
            val kind=bytes.copyOfRange(i+4,i+8).toString(Charsets.US_ASCII)
            if(kind!="nclx" && kind!="nclc")continue
            fun u16(at:Int)=((bytes[at].toInt() and 255) shl 8)+(bytes[at+1].toInt() and 255)
            if(u16(i+8)==9 && u16(i+10)==18)return true
        }
        return false
    }
}
