package net.opens3

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.LocalDate
import org.json.JSONObject

fun connectToDB() {
    Database.connect(
        "jdbc:mysql://${DB_ADDRESS}/${DB_NAME}?useSSL=false",
        "com.mysql.jdbc.Driver",
        user = DB_USERNAME,
        password = DB_PASSWORD
    )
}

fun retrieveToken(): String {
    var ourToken = "NULL"
    connectToDB()
    transaction {
        SchemaUtils.create(APIToken)
        val tokenId = APIToken.selectAll()
        for (token in tokenId) {
            ourToken = token[APIToken.accessToken]
        }
    }
    return ourToken
}

fun retrieveNumber(): String {
    var ourNumber = "NULL"
    connectToDB()
    transaction {
        SchemaUtils.create(ProvisionedNumber)
        val tokenId = ProvisionedNumber.selectAll()
        for (token in tokenId) {
            ourNumber = token[ProvisionedNumber.destinationAddress]
        }
    }
    return ourNumber
}

fun provisionNumber() {
    runBlocking {
        val token = retrieveToken()

        val response = khttp.post(
            url = "https://tapi.telstra.com/v2/messages/provisioning/subscriptions",
            //url = "http://127.0.0.1:8080/foo",
            headers = mapOf(
                "content-type" to "application/json",
                "authorization" to "Bearer $token",
                "cache-control" to "no-cache"
            ),
            data = "{ \"activeDays\": 30 }"
        )
        println(response)
        if (response.statusCode == 201) {
            val obj: JSONObject = response.jsonObject
            println(obj)
            connectToDB()
            transaction {
                SchemaUtils.create(ProvisionedNumber)
                ProvisionedNumber.deleteAll()
                ProvisionedNumber.insert {
                    it[destinationAddress] = obj["destinationAddress"].toString()
                    it[expiryDate] = obj["expiryDate"].toString()
                }
            }
        } else {
            println("Error provisioning number.")
            println(response.text)
        }
    }
}

fun updateToken() {
    runBlocking {
        for (i in 0..3) {
            println("Update attempt $i")
            try {
                val payload = mapOf(
                    "grant_type" to "client_credentials",
                    "scope" to "NSMS",
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET
                )

                val response = khttp.post(
                    url = " https://tapi.telstra.com/v2/oauth/token",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = payload
                )

                if (response.statusCode == 200) {
                    println("Success")
                    val obj: JSONObject = response.jsonObject
                    connectToDB()
                    transaction {
                        SchemaUtils.create(APIToken)
                        APIToken.deleteAll()
                        val tokenId = APIToken.insert {
                            it[accessToken] = obj["access_token"].toString()
                            it[expiresIn] = obj["expires_in"].toString()
                        }
                        println("Token added with id: ${tokenId[APIToken.id]}")
                    }
                    break
                } else {
                    println(response.statusCode)
                    println(response.text)
                }
            } catch (e: Error) {
                println(e)
            }
        }
    }
}

fun getMSG(): MessageObject? {
    var tries = 0
    while (tries < 3) {
        val token = retrieveToken()
        val response = khttp.get(
            url = " https://tapi.telstra.com/v2/messages/sms",
            headers = mapOf("content-type" to "application/json", "authorization" to "Bearer $token")
        )
        when (response.statusCode) {
            200 -> {
                val gson = Gson()
                try {
                    val msg = gson.fromJson(response.text, MessageObject::class.java)
                    if (msg.status == "EMPTY") {
                        return null
                    }
                    transaction {
                        SchemaUtils.create(Message)
                        Message.insert {
                            it[apiMsgId] = msg.apiMsgId
                            it[destinationAddress] = msg.destinationAddress
                            it[message] = msg.message
                            it[messageId] = msg.messageId
                            it[senderAddress] = msg.senderAddress
                            it[sentTimestamp] = DateTime(msg.sentTimestamp)
                            it[status] = msg.status
                        }
                    }
                    return msg

                } catch (e: Error) {
                    println("Could not parse message object.")
                }
            }
            401 -> {
                println("token expired, updating...")
                updateToken()
                tries++
            }
            400 -> {
                println("number not provisioned, provisioning....")
                provisionNumber()
                tries++
            }
            else -> {
                println("Unknown response code.")
            }
        }
    }
    return null
}

fun sendMsgSimple(mobile: String, body: String?): Boolean {
    if (body == null) return false
    var msgSent = false
    runBlocking {
        var ourNumber = retrieveNumber()
        val ourJson = json {
            "to" to mobile.replace("[\\s]+".toRegex(), "")
            "body" to body
            "from" to ourNumber
            "validity" to "5"
            "scheduledDelivery" to "1"
            "replyRequest" to "false"
            "priority" to "true"
        }
        // send it!
            for (i in 0..3) {
                var token = retrieveToken()
                println("Send attempt ${i}")
                try {
                    val response = khttp.post(
                        url = " https://tapi.telstra.com/v2/messages/sms",
                        //url = "http://127.0.0.1:8080/foo",
                        headers = mapOf("content-type" to "application/json", "authorization" to "Bearer ${token}"),
                        data = ourJson.toString()
                    )
                    if (response.statusCode == 201) {
                        msgSent = true
                        println("Message forwarded to $mobile")
                        break
                    } else if (response.statusCode == 401) {
                        println("token expired, updating...")
                        updateToken()
                    } else if (response.statusCode == 400) {
                        println("number not provisioned, provisioning....")
                        provisionNumber()
                    } else {
                        println(response.statusCode)
                        println(response.content)
                    }
                } catch (e: Error) {
                    println(e)
                }
            }
    }
    return msgSent;
}