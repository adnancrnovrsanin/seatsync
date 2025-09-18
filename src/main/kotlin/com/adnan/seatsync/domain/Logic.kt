package com.adnan.seatsync.domain

import com.adnan.seatsync.domain.model.Event
import com.adnan.seatsync.domain.model.PurchaseRequest
import com.adnan.seatsync.domain.model.Ticket
import com.adnan.seatsync.domain.model.TicketId
import com.adnan.seatsync.domain.model.TicketStatus
import com.adnan.seatsync.domain.model.UserId
import com.adnan.seatsync.util.Either
import com.adnan.seatsync.util.left
import com.adnan.seatsync.util.right
import java.time.Instant
import java.util.UUID

fun purchase(
    event: Event?,
    existingForEvent: Int,
    req: PurchaseRequest,
    now: Instant = Instant.now()
): Either<DomainError, List<Ticket>> {
    if (req.quantity <= 0) return left(DomainError.NonPositiveQuantity)
    val e = event ?: return left(DomainError.EventNotFound)
    if (e.isPast) return left(DomainError.EventInPast)
    if (req.quantity > e.maxPerRequest) return left(DomainError.OverMaxPerRequest)
    if (req.quantity > e.remaining) return left(DomainError.SoldOut)

    val tickets = (1..req.quantity).map {
        Ticket(
            id = TicketId(UUID.randomUUID()),
            eventId = e.id,
            userId = req.userId,
            purchasedAt = now,
            status = TicketStatus.ACTIVE
        )
    }
    return right(tickets);
}

fun cancelTicket(
    ticket: Ticket,
    by: UserId
): Either<DomainError, Ticket> =
    when {
        ticket.userId != by -> left(DomainError.NotOwner)
        ticket.status == TicketStatus.CANCELLED -> left(DomainError.AlreadyCancelled)
        else -> right(ticket.copy(status = TicketStatus.CANCELLED))
    }

fun expireIfPast(
    event: Event,
    ticket: Ticket,
    now: Instant = Instant.now()
): Ticket =
    if (now.isAfter(event.startsAt) && ticket.status == TicketStatus.ACTIVE)
        ticket.copy(status = TicketStatus.EXPIRED)
    else ticket