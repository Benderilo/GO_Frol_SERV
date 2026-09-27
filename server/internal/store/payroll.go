package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ---------- Зарплаты: аванс, зарплата и даты выплат ----------

// Виды выплат сотруднику.
const (
	PayAdvance = "advance"
	PaySalary  = "salary"
)

// Аванс считается за первую половину месяца: дни с 1-го по это число.
const advanceLastDay = 15

// PayrollSettings — дни выплат по умолчанию: аванс — такого-то числа
// расчётного месяца, зарплата — такого-то числа следующего.
type PayrollSettings struct {
	AdvanceDay int `json:"advanceDay"`
	SalaryDay  int `json:"salaryDay"`
}

// SalaryPayment — выплата сотруднику за месяц. Деньги — строка кассы CashOpID.
type SalaryPayment struct {
	ID         int64  `json:"id"`
	WorkerID   int64  `json:"workerId"`
	WorkerName string `json:"workerName"`
	Month      string `json:"month"`
	Kind       string `json:"kind"`
	AmountKop  int64  `json:"amountKop"`
	PaidOn     string `json:"paidOn"`
	Method     string `json:"method"`
	CashOpID   int64  `json:"cashOpId"`
	Note       string `json:"note"`
	CreatedAt  string `json:"createdAt"`
}

// SalaryPaymentInput — то, что присылают приложение и панель.
type SalaryPaymentInput struct {
	WorkerID  int64  `json:"workerId"`
	Month     string `json:"month"`
	Kind      string `json:"kind"`
	AmountKop int64  `json:"amountKop"`
	PaidOn    string `json:"paidOn"`
	Method    string `json:"method"`
	Note      string `json:"note"`
}

// PayrollRow — расчёт по одному сотруднику за месяц.
//
// Аванс — начисленное за первую половину месяца (у окладника — половина
// оклада), зарплата — остаток начисления. «Осталось» считается от
// фактически выплаченного, поэтому переплата аванса уменьшает зарплату.
type PayrollRow struct {
	WorkerID   int64  `json:"workerId"`
	Name       string `json:"name"`
	Position   string `json:"position"`
	Phone      string `json:"phone"`
	Active     bool   `json:"active"`
	SalaryType string `json:"salaryType"`
	SalaryKop  int64  `json:"salaryKop"`

	Days           int   `json:"days"`
	AdvanceDays    int   `json:"advanceDays"`
	AccruedKop     int64 `json:"accruedKop"`
	AdvancePlanKop int64 `json:"advancePlanKop"`
	SalaryPlanKop  int64 `json:"salaryPlanKop"`

	AdvancePaidKop int64 `json:"advancePaidKop"`
	SalaryPaidKop  int64 `json:"salaryPaidKop"`
	// Сколько ещё выдать авансом и зарплатой; вместе — весь долг за месяц.
	AdvanceLeftKop int64 `json:"advanceLeftKop"`
	SalaryLeftKop  int64 `json:"salaryLeftKop"`
	// Начислено минус выплачено; отрицательное — выдали больше начисленного.
	BalanceKop int64 `json:"balanceKop"`

	Payments []SalaryPayment `json:"payments"`
}

// Payroll — зарплатная ведомость за месяц.
type Payroll struct {
	Month       string `json:"month"`
	AdvanceDate string `json:"advanceDate"`
	SalaryDate  string `json:"salaryDate"`
	// Даты этого месяца перенесены вручную, а не взяты из дней по умолчанию.
	DatesCustom bool            `json:"datesCustom"`
	Settings    PayrollSettings `json:"settings"`
	Rows        []PayrollRow    `json:"rows"`

	AccruedKop     int64 `json:"accruedKop"`
	PaidKop        int64 `json:"paidKop"`
	AdvanceLeftKop int64 `json:"advanceLeftKop"`
	SalaryLeftKop  int64 `json:"salaryLeftKop"`
}

func parseMonth(month string) (time.Time, error) {
	t, err := time.Parse("2006-01", strings.TrimSpace(month))
	if err != nil {
		return time.Time{}, fmt.Errorf("%w: месяц должен быть в формате ГГГГ-ММ", ErrBadName)
	}
	return t, nil
}

func parseDay(date string) (time.Time, error) {
	t, err := time.Parse("2006-01-02", strings.TrimSpace(date))
	if err != nil {
		return time.Time{}, fmt.Errorf("%w: дата должна быть в формате ГГГГ-ММ-ДД", ErrBadName)
	}
	return t, nil
}

// dayInMonth — число day в месяце m; 31-е в коротком месяце становится последним днём.
func dayInMonth(m time.Time, day int) string {
	last := m.AddDate(0, 1, -1).Day()
	if day > last {
		day = last
	}
	if day < 1 {
		day = 1
	}
	return time.Date(m.Year(), m.Month(), day, 0, 0, 0, 0, time.UTC).Format("2006-01-02")
}

func (s *Store) PayrollSettings(ctx context.Context) (PayrollSettings, error) {
	var ps PayrollSettings
	err := s.db.QueryRowContext(ctx,
		`SELECT advance_day, salary_day FROM payroll_settings WHERE id = 1`).Scan(&ps.AdvanceDay, &ps.SalaryDay)
	if errors.Is(err, sql.ErrNoRows) {
		return PayrollSettings{AdvanceDay: 20, SalaryDay: 5}, nil
	}
	return ps, err
}

func (s *Store) SavePayrollSettings(ctx context.Context, ps PayrollSettings) (PayrollSettings, error) {
	if ps.AdvanceDay < 1 || ps.AdvanceDay > 31 || ps.SalaryDay < 1 || ps.SalaryDay > 31 {
		return PayrollSettings{}, fmt.Errorf("%w: день выплаты — число от 1 до 31", ErrBadName)
	}
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO payroll_settings (id, advance_day, salary_day) VALUES (1, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET advance_day = excluded.advance_day, salary_day = excluded.salary_day`,
		ps.AdvanceDay, ps.SalaryDay); err != nil {
		return PayrollSettings{}, err
	}
	s.AddAudit(ctx, "payroll", 0, "settings",
		fmt.Sprintf("аванс %d-го, зарплата %d-го", ps.AdvanceDay, ps.SalaryDay))
	return ps, nil
}

// payrollDates — даты выплат за месяц: перенесённые вручную или по умолчанию.
func (s *Store) payrollDates(ctx context.Context, m time.Time, ps PayrollSettings) (advance, salary string, custom bool, err error) {
	err = s.db.QueryRowContext(ctx,
		`SELECT advance_date, salary_date FROM payroll_dates WHERE work_month = ?`,
		m.Format("2006-01")).Scan(&advance, &salary)
	if err == nil {
		return advance, salary, true, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", "", false, err
	}
	return dayInMonth(m, ps.AdvanceDay), dayInMonth(m.AddDate(0, 1, 0), ps.SalaryDay), false, nil
}

// SetPayrollDates переносит даты выплат одного месяца. Обе пустые — вернуть
// дни по умолчанию.
func (s *Store) SetPayrollDates(ctx context.Context, month, advance, salary string) error {
	m, err := parseMonth(month)
	if err != nil {
		return err
	}
	key := m.Format("2006-01")
	advance, salary = strings.TrimSpace(advance), strings.TrimSpace(salary)
	if advance == "" && salary == "" {
		if _, err := s.db.ExecContext(ctx, `DELETE FROM payroll_dates WHERE work_month = ?`, key); err != nil {
			return err
		}
		s.AddAudit(ctx, "payroll", 0, "dates_reset", key)
		return nil
	}
	ps, err := s.PayrollSettings(ctx)
	if err != nil {
		return err
	}
	defAdvance, defSalary, _, err := s.payrollDates(ctx, m, ps)
	if err != nil {
		return err
	}
	if advance == "" {
		advance = defAdvance
	}
	if salary == "" {
		salary = defSalary
	}
	if _, err := parseDay(advance); err != nil {
		return err
	}
	if _, err := parseDay(salary); err != nil {
		return err
	}
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO payroll_dates (work_month, advance_date, salary_date) VALUES (?, ?, ?)
		 ON CONFLICT(work_month) DO UPDATE SET advance_date = excluded.advance_date, salary_date = excluded.salary_date`,
		key, advance, salary); err != nil {
		return err
	}
	s.AddAudit(ctx, "payroll", 0, "dates", fmt.Sprintf("%s: аванс %s, зарплата %s", key, advance, salary))
	return nil
}

// Payroll собирает ведомость за месяц: все работающие плюс уволенные,
// у которых в этом месяце были выходы или выплаты.
func (s *Store) Payroll(ctx context.Context, month string) (Payroll, error) {
	m, err := parseMonth(month)
	if err != nil {
		return Payroll{}, err
	}
	key := m.Format("2006-01")
	ps, err := s.PayrollSettings(ctx)
	if err != nil {
		return Payroll{}, err
	}
	out := Payroll{Month: key, Settings: ps, Rows: []PayrollRow{}}
	if out.AdvanceDate, out.SalaryDate, out.DatesCustom, err = s.payrollDates(ctx, m, ps); err != nil {
		return Payroll{}, err
	}

	workers, err := s.ListWorkers(ctx, true)
	if err != nil {
		return Payroll{}, err
	}

	days := map[int64][2]int{} // всего, из них в первой половине
	rows, err := s.db.QueryContext(ctx,
		`SELECT worker_id, COUNT(*), SUM(CASE WHEN CAST(substr(work_date, 9, 2) AS INTEGER) <= ? THEN 1 ELSE 0 END)
		 FROM work_days WHERE substr(work_date, 1, 7) = ? GROUP BY worker_id`, advanceLastDay, key)
	if err != nil {
		return Payroll{}, err
	}
	for rows.Next() {
		var id int64
		var all, first int
		if err := rows.Scan(&id, &all, &first); err != nil {
			rows.Close()
			return Payroll{}, err
		}
		days[id] = [2]int{all, first}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return Payroll{}, err
	}

	payments, err := s.ListSalaryPayments(ctx, key, 0)
	if err != nil {
		return Payroll{}, err
	}
	byWorker := map[int64][]SalaryPayment{}
	for _, p := range payments {
		byWorker[p.WorkerID] = append(byWorker[p.WorkerID], p)
	}

	for _, w := range workers {
		d, worked := days[w.ID]
		paid := byWorker[w.ID]
		if !w.Active && !worked && len(paid) == 0 {
			continue
		}
		row := PayrollRow{
			WorkerID: w.ID, Name: w.Name, Position: w.Position, Phone: w.Phone, Active: w.Active,
			SalaryType: w.SalaryType, SalaryKop: w.SalaryKop,
			Days: d[0], AdvanceDays: d[1], Payments: paid,
		}
		if row.Payments == nil {
			row.Payments = []SalaryPayment{}
		}
		if w.SalaryType == "month" {
			row.AccruedKop = w.SalaryKop
			row.AdvancePlanKop = w.SalaryKop / 2
		} else {
			row.AccruedKop = int64(d[0]) * w.SalaryKop
			row.AdvancePlanKop = int64(d[1]) * w.SalaryKop
		}
		row.SalaryPlanKop = row.AccruedKop - row.AdvancePlanKop
		for _, p := range paid {
			if p.Kind == PayAdvance {
				row.AdvancePaidKop += p.AmountKop
			} else {
				row.SalaryPaidKop += p.AmountKop
			}
		}
		row.BalanceKop = row.AccruedKop - row.AdvancePaidKop - row.SalaryPaidKop
		if left := row.AdvancePlanKop - row.AdvancePaidKop; left > 0 {
			row.AdvanceLeftKop = min(left, max(row.BalanceKop, 0))
		}
		row.SalaryLeftKop = max(row.BalanceKop-row.AdvanceLeftKop, 0)

		out.AccruedKop += row.AccruedKop
		out.PaidKop += row.AdvancePaidKop + row.SalaryPaidKop
		out.AdvanceLeftKop += row.AdvanceLeftKop
		out.SalaryLeftKop += row.SalaryLeftKop
		out.Rows = append(out.Rows, row)
	}
	return out, nil
}

const salarySelect = `SELECT p.id, p.worker_id, COALESCE(w.name, ''), p.work_month, p.kind, p.amount_kop,
	p.paid_on, COALESCE(c.method, 'cash'), p.cash_op_id, p.note, p.created_at
	FROM salary_payments p
	LEFT JOIN workers w ON w.id = p.worker_id
	LEFT JOIN cash_ops c ON c.id = p.cash_op_id`

// ListSalaryPayments — выплаты за месяц (пусто — за всё время); workerID 0 — всем.
func (s *Store) ListSalaryPayments(ctx context.Context, month string, workerID int64) ([]SalaryPayment, error) {
	rows, err := s.db.QueryContext(ctx, salarySelect+`
		WHERE (? = '' OR p.work_month = ?) AND (? = 0 OR p.worker_id = ?)
		ORDER BY p.paid_on, p.id`, month, month, workerID, workerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]SalaryPayment, 0, 16)
	for rows.Next() {
		p, err := scanSalaryPayment(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) SalaryPayment(ctx context.Context, id int64) (SalaryPayment, error) {
	rows, err := s.db.QueryContext(ctx, salarySelect+` WHERE p.id = ?`, id)
	if err != nil {
		return SalaryPayment{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return SalaryPayment{}, err
		}
		return SalaryPayment{}, ErrNotFound
	}
	return scanSalaryPayment(rows)
}

func scanSalaryPayment(rows *sql.Rows) (SalaryPayment, error) {
	var p SalaryPayment
	err := rows.Scan(&p.ID, &p.WorkerID, &p.WorkerName, &p.Month, &p.Kind, &p.AmountKop,
		&p.PaidOn, &p.Method, &p.CashOpID, &p.Note, &p.CreatedAt)
	return p, err
}

// checkSalaryPayment приводит ввод к каноническому виду.
func checkSalaryPayment(in *SalaryPaymentInput) error {
	in.Kind = strings.TrimSpace(in.Kind)
	if in.Kind == "" {
		in.Kind = PaySalary
	}
	if in.Kind != PayAdvance && in.Kind != PaySalary {
		return fmt.Errorf("%w: вид выплаты — advance или salary", ErrBadName)
	}
	if in.AmountKop <= 0 {
		return fmt.Errorf("%w: сумма выплаты должна быть больше нуля", ErrBadName)
	}
	m, err := parseMonth(in.Month)
	if err != nil {
		return err
	}
	in.Month = m.Format("2006-01")
	in.PaidOn = strings.TrimSpace(in.PaidOn)
	if in.PaidOn == "" {
		in.PaidOn = time.Now().UTC().Format("2006-01-02")
	}
	if _, err := parseDay(in.PaidOn); err != nil {
		return err
	}
	if !ValidMethod(in.Method) {
		in.Method = MethodCash
	}
	in.Note = strings.TrimSpace(in.Note)
	return nil
}

func payKindName(kind string) string {
	if kind == PayAdvance {
		return "Аванс"
	}
	return "Зарплата"
}

func salaryCashNote(kind, name, month, note string) string {
	text := fmt.Sprintf("%s: %s, %s", payKindName(kind), name, month)
	if note != "" {
		text += " — " + note
	}
	return text
}

// paidOnMoment — момент движения денег для кассы: 09:00 UTC, полдень по
// Москве, чтобы дата не съехала на соседний день ни в одном поясе России.
func paidOnMoment(date string) string { return date + "T09:00:00Z" }

// AddSalaryPayment записывает выплату: расход кассы со статьёй «Зарплата»
// и строку выплаты, привязанную к этому расходу.
func (s *Store) AddSalaryPayment(ctx context.Context, in SalaryPaymentInput) (SalaryPayment, error) {
	if err := checkSalaryPayment(&in); err != nil {
		return SalaryPayment{}, err
	}
	w, err := s.GetWorker(ctx, in.WorkerID)
	if err != nil {
		return SalaryPayment{}, err
	}
	op, err := s.AddCashOp(ctx, CashOp{
		Direction:  DirectionOut,
		AmountKop:  in.AmountKop,
		Method:     in.Method,
		Category:   "Зарплата",
		Note:       salaryCashNote(in.Kind, w.Name, in.Month, in.Note),
		HappenedAt: paidOnMoment(in.PaidOn),
	})
	if err != nil {
		return SalaryPayment{}, err
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO salary_payments (worker_id, work_month, kind, amount_kop, paid_on, cash_op_id, note, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		w.ID, in.Month, in.Kind, in.AmountKop, in.PaidOn, op.ID, in.Note, now())
	if err != nil {
		// Без строки выплаты расход в кассе был бы «ничьим» — убираем его.
		_ = s.DeleteCashOp(ctx, op.ID)
		return SalaryPayment{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return SalaryPayment{}, err
	}
	s.AddAudit(ctx, "worker", w.ID, "payout",
		fmt.Sprintf("%s %s: %s за %s, %s", payKindName(in.Kind), w.Name, moneyText(in.AmountKop), in.Month, in.PaidOn))
	return s.SalaryPayment(ctx, id)
}

// UpdateSalaryPayment правит выплату: сумму, дату, вид, месяц — и вместе
// с ней строку кассы, чтобы деньги и ведомость говорили одно и то же.
func (s *Store) UpdateSalaryPayment(ctx context.Context, id int64, in SalaryPaymentInput) (SalaryPayment, error) {
	cur, err := s.SalaryPayment(ctx, id)
	if err != nil {
		return SalaryPayment{}, err
	}
	in.WorkerID = cur.WorkerID
	if err := checkSalaryPayment(&in); err != nil {
		return SalaryPayment{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return SalaryPayment{}, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx,
		`UPDATE cash_ops SET amount_kop = ?, method = ?, note = ?, happened_at = ? WHERE id = ?`,
		in.AmountKop, in.Method, salaryCashNote(in.Kind, cur.WorkerName, in.Month, in.Note),
		paidOnMoment(in.PaidOn), cur.CashOpID); err != nil {
		return SalaryPayment{}, err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE salary_payments SET work_month = ?, kind = ?, amount_kop = ?, paid_on = ?, note = ? WHERE id = ?`,
		in.Month, in.Kind, in.AmountKop, in.PaidOn, in.Note, id); err != nil {
		return SalaryPayment{}, err
	}
	if err := tx.Commit(); err != nil {
		return SalaryPayment{}, err
	}
	s.AddAudit(ctx, "worker", cur.WorkerID, "payout_update",
		fmt.Sprintf("%s %s: %s за %s, %s", payKindName(in.Kind), cur.WorkerName, moneyText(in.AmountKop), in.Month, in.PaidOn))
	return s.SalaryPayment(ctx, id)
}

// DeleteSalaryPayment удаляет выплату вместе с расходом в кассе
// (строка выплаты уходит каскадом за строкой кассы).
func (s *Store) DeleteSalaryPayment(ctx context.Context, id int64) error {
	p, err := s.SalaryPayment(ctx, id)
	if err != nil {
		return err
	}
	if err := s.DeleteCashOp(ctx, p.CashOpID); err != nil {
		return err
	}
	s.AddAudit(ctx, "worker", p.WorkerID, "payout_delete",
		fmt.Sprintf("%s %s: %s за %s", payKindName(p.Kind), p.WorkerName, moneyText(p.AmountKop), p.Month))
	return nil
}
