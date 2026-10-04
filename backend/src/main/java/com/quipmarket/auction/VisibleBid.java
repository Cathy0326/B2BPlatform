package com.quipmarket.auction;

import java.time.Instant;

/** A row of the public bid history. `auto` = placed by the proxy on someone's behalf. */
public record VisibleBid(String bidderId, long amountCents, boolean auto, Instant at) {}
