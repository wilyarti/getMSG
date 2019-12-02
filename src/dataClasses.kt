package net.opens3

import org.jetbrains.exposed.sql.Table

data class msg_form (
    val to: String,
    val body: String,
    val from: String,
    val validity: String = "5",
    val scheduledDelivery: String = "1",
    val replyRequest: String ="false",
    val priority: String = "true"
)

data class API_TOKEN (
    val access_token: String,
    val expires_in: String
)
data class Provisioned_Number (
    val destinationAddress: String,
    val expiryDate: String
)
object APIToken : Table() {
    val id = integer("id").autoIncrement().primaryKey() // Column<Int>
    val accessToken = varchar("accessToken", length = 150) // Column<String>
    val expiresIn = varchar("expiresIn", length = 150) // Column<String>
}
object ProvisionedNumber : Table() {
    val id = integer("id").autoIncrement().primaryKey() // Column<Int>
    val destinationAddress = varchar("destinationAddress", length = 150) // Column<String>
    val expiryDate = varchar("expiryDate", length = 150) // Column<String>
}

data class Messages(
    val messages: List<MessageObject>
)

object Message : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val apiMsgId = varchar("apiMsgId", length=150)
    val destinationAddress = varchar("destinationAddress", length = 150)
    val message = text("message")
    val messageId = varchar("messageId", length=150)
    val senderAddress = varchar("senderAddress", length=150)
    val sentTimestamp = datetime("sentTimeStamp")
    val status = varchar("status", length=150)
}
data class MessageObject(
    val apiMsgId: String,
    val destinationAddress: String,
    val message: String,
    val messageId: String,
    val senderAddress: String,
    val sentTimestamp: String,
    val status: String
)