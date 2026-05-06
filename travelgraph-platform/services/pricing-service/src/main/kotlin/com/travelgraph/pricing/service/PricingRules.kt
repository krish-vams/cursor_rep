package com.travelgraph.pricing.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * Static pricing-rule definitions kept in code (not in DB) so the rules can
 * evolve without migrations. Each rule is independently applied; the service
 * layer composes them in the documented order.
 */
object PricingRules {

    /** Weekend = Friday or Saturday night. Charged a flat 20% uplift. */
    const val WEEKEND_UPLIFT_PERCENT: Double = 20.0

    /**
     * Holidays = a small fixed set used for the Phase 1 demo. In production this
     * comes from a managed calendar service. Each entry is a (month, day-of-month)
     * pair, year-agnostic. Charged a flat 35% uplift on top of any weekend uplift.
     */
    val HOLIDAYS: Set<MonthDay> = setOf(
        MonthDay.of(1, 1),    // New Year's Day
        MonthDay.of(2, 14),   // Valentine's Day
        MonthDay.of(7, 4),    // Independence Day
        MonthDay.of(10, 31),  // Halloween
        MonthDay.of(12, 24),  // Christmas Eve
        MonthDay.of(12, 25),  // Christmas
        MonthDay.of(12, 31),  // New Year's Eve
    )
    const val HOLIDAY_UPLIFT_PERCENT: Double = 35.0

    /** Loyalty tiers: tier name -> percentage discount applied AFTER uplifts and BEFORE tax. */
    val LOYALTY_DISCOUNTS: Map<LoyaltyTier, Double> = mapOf(
        LoyaltyTier.NONE to 0.0,
        LoyaltyTier.SILVER to 5.0,
        LoyaltyTier.GOLD to 10.0,
        LoyaltyTier.PLATINUM to 15.0,
    )

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.FRIDAY || date.dayOfWeek == DayOfWeek.SATURDAY

    fun isHoliday(date: LocalDate): Boolean =
        MonthDay.of(date.monthValue, date.dayOfMonth) in HOLIDAYS
}

enum class LoyaltyTier { NONE, SILVER, GOLD, PLATINUM }
