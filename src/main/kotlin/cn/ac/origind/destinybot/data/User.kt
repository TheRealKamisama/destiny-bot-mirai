package cn.ac.origind.destinybot.data

data class User(
    var qq: Long = -1L,
    var bungieMembershipId: String? = "",
    var destinyMembershipType: Int = 3,
    var destinyMembershipId: String? = "",
    var destinyDisplayName: String? = "",
    var isAdmin: Boolean = false
)