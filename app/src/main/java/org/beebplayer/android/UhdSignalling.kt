// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

object UhdSignalling {
    fun hlg(bytes: ByteArray): Boolean {
        for(i in 4 until bytes.size-13) {
            if(bytes[i]!=99.toByte() || bytes[i+1]!=111.toByte() || bytes[i+2]!=108.toByte() || bytes[i+3]!=114.toByte()) continue
            val kind=bytes.copyOfRange(i+4,i+8).toString(Charsets.US_ASCII)
            if(kind!="nclx" && kind!="nclc")continue
            fun u16(at:Int)=((bytes[at].toInt() and 255) shl 8)+(bytes[at+1].toInt() and 255)
            if(u16(i+8)==9 && u16(i+10)==18)return true
        }
        return false
    }
}
