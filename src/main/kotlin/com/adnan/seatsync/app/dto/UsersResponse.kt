import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsersResponse(
    val users: List<User>,
    val total: Int,
    val skip: Int,
    val limit: Int
)

@Serializable
data class User(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val maidenName: String? = null,
    val age: Int,
    val gender: Gender,
    val email: String,
    val phone: String,
    val username: String,
    val password: String,
    @SerialName("birthDate") val birthDate: String, // vidi "LocalDate" varijantu ispod
    val image: String? = null,
    val bloodGroup: String? = null,
    val height: Double? = null,
    val weight: Double? = null,
    val eyeColor: String? = null,
    val hair: Hair? = null,
    val ip: String? = null,
    val address: Address? = null,
    val macAddress: String? = null,
    val university: String? = null,
    val bank: Bank? = null,
    val company: Company? = null,
    val ein: String? = null,
    val ssn: String? = null,
    val userAgent: String? = null,
    val crypto: Crypto? = null,
    val role: Role = Role.USER
)

@Serializable
enum class Gender { @SerialName("male") MALE, @SerialName("female") FEMALE }

@Serializable
enum class Role {
    @SerialName("admin") ADMIN,
    @SerialName("moderator") MODERATOR,
    @SerialName("user") USER
}

@Serializable
data class Hair(
    val color: String? = null,
    val type: String? = null
)

@Serializable
data class Address(
    val address: String,
    val city: String,
    val state: String,
    val stateCode: String? = null,
    val postalCode: String,
    val coordinates: Coordinates? = null,
    val country: String
)

@Serializable
data class Coordinates(
    val lat: Double,
    val lng: Double
)

@Serializable
data class Bank(
    val cardExpire: String? = null,
    val cardNumber: String? = null,
    val cardType: String? = null,
    val currency: String? = null,
    val iban: String? = null
)

@Serializable
data class Company(
    val department: String? = null,
    val name: String,
    val title: String? = null,
    val address: Address? = null
)

@Serializable
data class Crypto(
    val coin: String? = null,
    val wallet: String? = null,
    val network: String? = null
)
