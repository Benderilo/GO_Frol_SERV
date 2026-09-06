package com.example.frolovsistems

import com.example.frolovsistems.ui.components.formatMoney
import com.example.frolovsistems.ui.components.formatQuantity
import com.example.frolovsistems.ui.components.moneyInput
import com.example.frolovsistems.ui.components.parseMoneyKop
import com.example.frolovsistems.ui.components.parseQuantityMilli
import com.example.frolovsistems.ui.components.quantityInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Весь смысл перехода на копейки — в том, что сумма не «плывёт». Разбор
 * идёт строкой, без Double, поэтому проверяем именно те случаи, на которых
 * дробное число теряет копейку.
 */
class MoneyFormatTest {

    @Test
    fun `рубли с копейками разбираются без потерь`() {
        assertEquals(199_999, parseMoneyKop("1999.99"))
        assertEquals(199_999, parseMoneyKop("1999,99"))
        assertEquals(1, parseMoneyKop("0.01"))
        assertEquals(4_850_050, parseMoneyKop("48 500,50"))
        assertEquals(1_500_000, parseMoneyKop("15000"))
    }

    /** «1999.5» — это 50 копеек, а не 5: недостающий знак дописывается нулём. */
    @Test
    fun `один знак после точки означает десятки копеек`() {
        assertEquals(199_950, parseMoneyKop("1999.5"))
        assertEquals(50, parseMoneyKop("0.5"))
    }

    /** Лишние знаки отбрасываются, а не округляют сумму вверх. */
    @Test
    fun `третий знак после точки отбрасывается`() {
        assertEquals(199_999, parseMoneyKop("1999.999"))
    }

    @Test
    fun `мусор и пустая строка дают ноль`() {
        assertEquals(0, parseMoneyKop(""))
        assertEquals(0, parseMoneyKop("   "))
        assertEquals(0, parseMoneyKop("абв"))
        assertEquals(0, parseMoneyKop("₽"))
    }

    /** Сотня платежей по 19.99 должна дать ровно 1999 ₽, а не 1998.99. */
    @Test
    fun `сумма ста платежей сходится до копейки`() {
        val once = parseMoneyKop("19.99")
        val total = (1..100).sumOf { once }
        assertEquals(199_900, total)
        assertEquals("1 999 ₽", formatMoney(total))
    }

    @Test
    fun `копейки печатаются только когда они есть`() {
        assertEquals("1 999 ₽", formatMoney(199_900))
        assertEquals("1 999,99 ₽", formatMoney(199_999))
        assertEquals("0,01 ₽", formatMoney(1))
        assertEquals("0 ₽", formatMoney(0))
        assertEquals("1 234 567 ₽", formatMoney(123_456_700))
    }

    @Test
    fun `отрицательная сумма печатается со знаком`() {
        assertEquals("−500 ₽", formatMoney(-50_000))
        assertEquals("−0,05 ₽", formatMoney(-5))
    }

    /** Поле ввода и разбор должны быть обратны друг другу. */
    @Test
    fun `текст поля и копейки переводятся туда и обратно`() {
        for (kop in listOf(1L, 50L, 199_999L, 1_500_000L, 123_456_789L)) {
            assertEquals(kop, parseMoneyKop(moneyInput(kop)))
        }
        assertEquals("", moneyInput(0))
    }

    @Test
    fun `количество разбирается до тысячных`() {
        assertEquals(2_500, parseQuantityMilli("2.5"))
        assertEquals(125, parseQuantityMilli("0.125"))
        assertEquals(95_250, parseQuantityMilli("95.25"))
        assertEquals(5_000, parseQuantityMilli("5"))
        // Четвёртый знак отбрасывается: точнее тысячной не считаем.
        assertEquals(1_234, parseQuantityMilli("1.2345"))
    }

    @Test
    fun `количество печатается без хвостовых нулей`() {
        assertEquals("5", formatQuantity(5_000))
        assertEquals("2,5", formatQuantity(2_500))
        assertEquals("0,125", formatQuantity(125))
        assertEquals("95,25", formatQuantity(95_250))
        assertEquals("−0,5", formatQuantity(-500))
    }

    @Test
    fun `текст количества и тысячные переводятся туда и обратно`() {
        for (milli in listOf(1L, 125L, 2_500L, 95_250L, 1_000_000L)) {
            assertEquals(milli, parseQuantityMilli(quantityInput(milli)))
        }
        assertEquals("", quantityInput(0))
    }
}
