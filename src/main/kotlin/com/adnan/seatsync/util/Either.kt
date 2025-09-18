package com.adnan.seatsync.util

sealed interface Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>
    data class Right<out R>(val value: R) : Either<Nothing, R>
}

inline fun <L,R,T> Either<L,R>.fold(fl: (L)->T, fr: (R)->T) = when(this){ is Either.Left->fl(value); is Either.Right->fr(value) }

fun <R> right(value:R)= Either.Right(value)
fun <L> left(value:L)= Either.Left(value)