package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusSegment

interface MessageFeed {
  suspend fun initial(): List<AmadeusSegment>
}