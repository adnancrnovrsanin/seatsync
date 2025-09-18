package com.adnan.seatsync.domain

sealed interface DomainError {
    data object EventNotFound : DomainError
    data object EventInPast : DomainError
    data object SoldOut : DomainError
    data object OverMaxPerRequest : DomainError
    data object NonPositiveQuantity : DomainError
    data object NotOwner : DomainError
    data object AlreadyCancelled : DomainError
}