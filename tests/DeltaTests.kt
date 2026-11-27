package com.aicommandcenter.app
import java.io.File
import java.io.DataOutputStream
fun main(args: Array<String>) {
    val dir = File(args.single()); val output = File(dir, "rebuilt.apk")
    for (name in listOf("shifted", "same", "random", "small", "reordered", "zip")) {
        val base = File(dir, "$name.base"); val target = File(dir, "$name.target"); val patch = File(dir, "$name.delta")
        ApkDelta.apply(base, patch, output, target.length())
        check(output.readBytes().contentEquals(target.readBytes())) { name }
        check(ApkDelta.digest(output) == ApkDelta.digest(target))
        println("$name: patch ${patch.length()} / target ${target.length()} bytes; exact reconstruction")
    }
    val base = File(dir,"shifted.base"); val target = File(dir,"shifted.target"); val bytes = File(dir,"shifted.delta").readBytes(); val bad = File(dir,"bad.delta")
    fun reject(label: String, data: ByteArray, input: File = base, size: Long = target.length()) {
        bad.writeBytes(data); output.delete()
        check(runCatching { ApkDelta.apply(input,bad,output,size) }.isFailure) { label }
        check(!output.exists()) { "Partial output remains: $label" }
    }
    reject("wrong magic", bytes.copyOf().apply { this[0]=0 })
    reject("wrong base", bytes, File(dir,"random.target"))
    reject("wrong size", bytes, size=target.length()+1)
    reject("truncated", bytes.copyOf(bytes.size-1))
    reject("trailing", bytes+1.toByte())
    reject("unknown op", bytes.copyOf().apply { this[48]=7 })
    fun malformed(op:Int, offset:Long, length:Int):ByteArray {
        val stream=java.io.ByteArrayOutputStream()
        DataOutputStream(stream).use { out -> out.write(bytes,0,48); out.writeByte(op); if(op==1) out.writeLong(offset); out.writeInt(length); out.writeByte(0) }
        return stream.toByteArray()
    }
    reject("negative offset",malformed(1,-1,1)); reject("out of bounds",malformed(1,base.length(),1))
    reject("negative length",malformed(2,0,-1)); reject("zero length",malformed(2,0,0))
    reject("oversized output",malformed(1,0,Int.MAX_VALUE)); reject("premature EOF",bytes.copyOf(48)+0.toByte())
    check(UpdateRules.usefulDelta(500,1000)); check(!UpdateRules.usefulDelta(900,1000)); check(!UpdateRules.usefulDelta(1001,1000)); check(!UpdateRules.usefulDelta(0,1000))
    check(UpdateRules.deltaName("v1.2.1002-abcdef0","a".repeat(64)) == "AICommandCenter-v1.2.1002-abcdef0-from-${"a".repeat(64)}.delta")
    println("6 reconstruction cases, 12 rejection cases, 5 selection checks passed")
}
