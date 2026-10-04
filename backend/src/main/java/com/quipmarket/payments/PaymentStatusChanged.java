package com.quipmarket.payments;

/**
 * Published (synchronously, inside the same transaction) whenever a payment's status changes,
 * whether from our own API call or from a provider webhook. Other modules react to it instead of
 * the payments module knowing about auctions or escrow.
 */
public record PaymentStatusChanged(String paymentId, Payment.Purpose purpose, String auctionId, String payerId,
                                   Payment.Status previous, Payment.Status current) {}
