package com.v2plus.app.enums

enum class RoutingType(val fileName: String) {
    NONE("custom_routing_none"),
    WHITE("custom_routing_white"),
    BLACK("custom_routing_black"),
    GLOBAL("custom_routing_global"),
    WHITE_IRAN("custom_routing_white_iran"),
    WHITE_RUSSIA("custom_routing_white_russia");

    companion object {
        fun fromIndex(index: Int): RoutingType {
            return when (index) {
                0 -> NONE
                1 -> WHITE
                2 -> BLACK
                3 -> GLOBAL
                4 -> WHITE_IRAN
                5 -> WHITE_RUSSIA
                else -> NONE
            }
        }
    }
}
