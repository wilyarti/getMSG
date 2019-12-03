package net.opens3

import java.util.regex.Pattern.UNIX_LINES

fun main(): Unit {
    while(true) {
        println("Getting messages.")
        val msg = getMSG()
        if (msg != null) {
            println(msg.status)
            if (!sendMsgSimple(OUR_NUMBER, "MSG(${msg?.senderAddress})\n${msg?.message}")) {
                println("Forwarding message failed.")
            }
        }
        Thread.sleep(1*1000*60*5)
    }
    //test()
}