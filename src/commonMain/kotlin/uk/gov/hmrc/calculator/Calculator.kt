/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.gov.hmrc.calculator

import uk.gov.hmrc.calculator.annotations.Throws
import uk.gov.hmrc.calculator.exception.InvalidHoursException
import uk.gov.hmrc.calculator.exception.InvalidPayPeriodException
import uk.gov.hmrc.calculator.exception.InvalidTaxBandException
import uk.gov.hmrc.calculator.exception.InvalidTaxCodeException
import uk.gov.hmrc.calculator.exception.InvalidTaxYearException
import uk.gov.hmrc.calculator.exception.InvalidWagesException
import uk.gov.hmrc.calculator.model.BandBreakdown
import uk.gov.hmrc.calculator.model.CalculatorResponse
import uk.gov.hmrc.calculator.model.CalculatorResponsePayPeriod
import uk.gov.hmrc.calculator.model.PayPeriod
import uk.gov.hmrc.calculator.model.PayPeriod.FOUR_WEEKLY
import uk.gov.hmrc.calculator.model.PayPeriod.MONTHLY
import uk.gov.hmrc.calculator.model.PayPeriod.WEEKLY
import uk.gov.hmrc.calculator.model.PayPeriod.YEARLY
import uk.gov.hmrc.calculator.model.TaxYear
import uk.gov.hmrc.calculator.model.bands.Band
import uk.gov.hmrc.calculator.model.bands.EmployeeNIBands
import uk.gov.hmrc.calculator.model.bands.EmployerNIBands
import uk.gov.hmrc.calculator.model.bands.TaxBands
import uk.gov.hmrc.calculator.model.taxcodes.AdjustedTaxFreeTCode
import uk.gov.hmrc.calculator.model.taxcodes.EmergencyTaxCode
import uk.gov.hmrc.calculator.model.taxcodes.KTaxCode
import uk.gov.hmrc.calculator.model.taxcodes.MarriageTaxCodes
import uk.gov.hmrc.calculator.model.taxcodes.NoTaxTaxCode
import uk.gov.hmrc.calculator.model.taxcodes.SingleBandTax
import uk.gov.hmrc.calculator.model.taxcodes.StandardTaxCode
import uk.gov.hmrc.calculator.model.taxcodes.TaxCode
import uk.gov.hmrc.calculator.utils.convertAmountFromYearlyToPayPeriod
import uk.gov.hmrc.calculator.utils.convertListOfBandBreakdownForPayPeriod
import uk.gov.hmrc.calculator.utils.convertWageToYearly
import uk.gov.hmrc.calculator.utils.taxcode.getTrueTaxFreeAmount
import uk.gov.hmrc.calculator.utils.taxcode.toTaxCode
import uk.gov.hmrc.calculator.utils.validation.WageValidator
import kotlin.jvm.JvmOverloads

class Calculator @JvmOverloads constructor(
    private val taxCode: String,
    private val wages: Double,
    private val payPeriod: PayPeriod,
    private val isPensionAge: Boolean = false,
    private val howManyAWeek: Double? = null,
    private val taxYear: TaxYear = TaxYear.currentTaxYear
) {

    private val bandBreakdown: MutableList<BandBreakdown> = mutableListOf()

    @Throws(
        InvalidTaxCodeException::class,
        InvalidTaxYearException::class,
        InvalidWagesException::class,
        InvalidPayPeriodException::class,
        InvalidHoursException::class,
        InvalidTaxBandException::class
    )
    fun run(): CalculatorResponse {
        if (!WageValidator.isAboveMinimumWages(wages) || !WageValidator.isBelowMaximumWages(wages)) {
            throw InvalidWagesException("Wages must be between 0 and 9999999.99")
        }
        val yearlyWages = wages.convertWageToYearly(
            payPeriod,
            howManyAWeek
        )
        val amountToAddToWages = if (taxCodeType is KTaxCode) (taxCodeType as KTaxCode).amountToAddToWages else null

        return createResponse(
            taxCodeType,
            yearlyWages,
            taxCodeType.getTrueTaxFreeAmount(),
            amountToAddToWages
        )
    }

    private fun createResponse(
        taxCode: TaxCode,
        yearlyWages: Double,
        taxFreeAmount: Double,
        amountToAddToWages: Double?
    ): CalculatorResponse {
        val taxPayable = taxToPay(
            yearlyWages,
            taxCode
        )
        val employeesNI = employeeNIToPay(yearlyWages)
        val employersNI = employerNIToPay(yearlyWages)
        return CalculatorResponse(
            country = taxCode.country,
            isKCode = taxCode is KTaxCode,
            weekly = CalculatorResponsePayPeriod(
                payPeriod = WEEKLY,
                taxToPayForPayPeriod = taxPayable.convertAmountFromYearlyToPayPeriod(WEEKLY),
                employeesNIRaw = employeesNI.convertAmountFromYearlyToPayPeriod(WEEKLY),
                employersNIRaw = employersNI.convertAmountFromYearlyToPayPeriod(WEEKLY),
                wagesRaw = yearlyWages.convertAmountFromYearlyToPayPeriod(WEEKLY),
                taxBreakdownForPayPeriod = bandBreakdown.convertListOfBandBreakdownForPayPeriod(WEEKLY),
                taxFreeRaw = taxFreeAmount.convertAmountFromYearlyToPayPeriod(WEEKLY),
                kCodeAdjustmentRaw = amountToAddToWages?.convertAmountFromYearlyToPayPeriod(WEEKLY)
            ),
            fourWeekly = CalculatorResponsePayPeriod(
                payPeriod = FOUR_WEEKLY,
                taxToPayForPayPeriod = taxPayable.convertAmountFromYearlyToPayPeriod(FOUR_WEEKLY),
                employeesNIRaw = employeesNI.convertAmountFromYearlyToPayPeriod(FOUR_WEEKLY),
                employersNIRaw = employersNI.convertAmountFromYearlyToPayPeriod(FOUR_WEEKLY),
                wagesRaw = yearlyWages.convertAmountFromYearlyToPayPeriod(FOUR_WEEKLY),
                taxBreakdownForPayPeriod = bandBreakdown.convertListOfBandBreakdownForPayPeriod(FOUR_WEEKLY),
                taxFreeRaw = taxFreeAmount.convertAmountFromYearlyToPayPeriod(FOUR_WEEKLY),
                kCodeAdjustmentRaw = amountToAddToWages?.convertAmountFromYearlyToPayPeriod(FOUR_WEEKLY)
            ),
            monthly = CalculatorResponsePayPeriod(
                payPeriod = MONTHLY,
                taxToPayForPayPeriod = taxPayable.convertAmountFromYearlyToPayPeriod(MONTHLY),
                employeesNIRaw = employeesNI.convertAmountFromYearlyToPayPeriod(MONTHLY),
                employersNIRaw = employersNI.convertAmountFromYearlyToPayPeriod(MONTHLY),
                wagesRaw = yearlyWages.convertAmountFromYearlyToPayPeriod(MONTHLY),
                taxBreakdownForPayPeriod = bandBreakdown.convertListOfBandBreakdownForPayPeriod(MONTHLY),
                taxFreeRaw = taxFreeAmount.convertAmountFromYearlyToPayPeriod(MONTHLY),
                kCodeAdjustmentRaw = amountToAddToWages?.convertAmountFromYearlyToPayPeriod(MONTHLY)
            ),
            yearly = CalculatorResponsePayPeriod(
                payPeriod = YEARLY,
                taxToPayForPayPeriod = taxPayable.convertAmountFromYearlyToPayPeriod(YEARLY),
                employeesNIRaw = employeesNI.convertAmountFromYearlyToPayPeriod(YEARLY),
                employersNIRaw = employersNI.convertAmountFromYearlyToPayPeriod(YEARLY),
                wagesRaw = yearlyWages.convertAmountFromYearlyToPayPeriod(YEARLY),
                taxBreakdownForPayPeriod = bandBreakdown,
                taxFreeRaw = taxFreeAmount,
                kCodeAdjustmentRaw = amountToAddToWages
            )
        )
    }

    private fun taxToPay(
        yearlyWages: Double,
        taxCode: TaxCode
    ): Double {
        return when (taxCode) {
            is StandardTaxCode, is AdjustedTaxFreeTCode, is EmergencyTaxCode, is MarriageTaxCodes -> {
                val taxBands = TaxBands.getBands(
                    taxYear,
                    taxCode.country
                )
                getTotalFromTaxBands(
                    taxBands,
                    yearlyWages
                )
            }
            is NoTaxTaxCode -> getTotalFromSingleBand(
                yearlyWages,
                taxCode.taxFreeAmount
            )
            is SingleBandTax -> {
                val taxBands = TaxBands.getBands(
                    taxYear,
                    taxCode.country
                )
                getTotalFromSingleBand(
                    yearlyWages,
                    taxBands[taxCode.taxAllAtBand].percentageAsDecimal
                )
            }
            is KTaxCode -> {
                val taxBands = TaxBands.getBands(
                    taxYear,
                    taxCode.country
                )
                getTotalFromTaxBands(
                    taxBands,
                    yearlyWages + taxCode.amountToAddToWages
                )
            }
            else -> throw InvalidTaxCodeException("$this is an invalid tax code")
        }
    }

    private fun getTotalFromSingleBand(
        yearlyWage: Double,
        percentageForSingleBand: Double
    ): Double {
        val taxToPayForSingleBand: Double = yearlyWage * percentageForSingleBand
        bandBreakdown.add(
            BandBreakdown(
                percentageForSingleBand,
                taxToPayForSingleBand
            )
        )
        return taxToPayForSingleBand
    }

    private fun employerNIToPay(yearlyWages: Double) =
        if (isPensionAge) 0.0 else getTotalFromNIBands(
            EmployerNIBands(taxYear).bands,
            yearlyWages
        )

    private fun employeeNIToPay(yearlyWages: Double) =
        if (isPensionAge) 0.0 else getTotalFromNIBands(
            EmployeeNIBands(taxYear).bands,
            yearlyWages
        )

    private fun getTotalFromTaxBands(
        bands: List<Band>,
        wages: Double
    ): Double {
        var amount = 0.0
        var remainingToTax = wages - taxCodeType.taxFreeAmount
        bands.map { band: Band ->
            if (remainingToTax <= 0) return amount

            val toTax = if ((band.upper - band.lower) < remainingToTax && band.upper != -1.0) {
                band.upper - band.lower
            } else {
                remainingToTax
            }
            val taxForBand = toTax * band.percentageAsDecimal
            bandBreakdown.add(
                BandBreakdown(
                    band.percentageAsDecimal,
                    taxForBand
                )
            )

            remainingToTax -= toTax
            amount += taxForBand
        }
        return amount
    }

    private fun getTotalFromNIBands(
        bands: List<Band>,
        wages: Double
    ): Double {
        var amount = 0.0
        var remainingToTax = wages - bands[0].lower
        bands.map { band: Band ->
            if (remainingToTax <= 0) return amount

            val toTax = if ((band.upper - band.lower) < remainingToTax && band.upper != -1.0) {
                band.upper - band.lower
            } else {
                remainingToTax
            }
            val taxForBand = toTax * band.percentageAsDecimal

            remainingToTax -= toTax
            amount += taxForBand
        }
        return amount
    }

    private val taxCodeType: TaxCode by lazy {
        this.taxCode.toTaxCode()
    }
}
