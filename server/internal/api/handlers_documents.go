package api

import (
	"bytes"
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/documents"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// handleOrderDocument отдаёт счёт или акт по заказу готовой страницей.
//
// HTML, а не PDF: страницу печатают из приложения системным диалогом,
// который сам сохраняет её в PDF. Так документ можно поправить в вёрстке
// под свою форму, не пересобирая сервер и не таща в него шрифты.
func (a *API) handleOrderDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	kind := r.PathValue("kind")
	if !store.ValidDocKind(kind) {
		writeError(w, http.StatusBadRequest, "validation", "Неизвестный вид документа")
		return
	}

	ctx := r.Context()
	company, err := a.store.Company(ctx)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	// Без наименования и ИНН документ выйдет обезличенным: такой клиенту
	// отдавать нельзя, и лучше сказать об этом до печати.
	if !company.Filled() {
		writeError(w, http.StatusConflict, "company_empty",
			"Не заполнены реквизиты ИП. Откройте «Реквизиты ИП» и впишите хотя бы наименование и ИНН.")
		return
	}

	order, err := a.store.Order(ctx, id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	items, err := a.store.OrderItems(ctx, id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	doc, err := a.store.IssueDocument(ctx, id, kind)
	if err != nil {
		writeStoreError(w, err)
		return
	}

	data := buildDocument(company, order, items, doc, a.clientOf(r, order))

	var page bytes.Buffer
	if err := documents.Render(&page, data); err != nil {
		writeStoreError(w, err)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(page.Bytes())
}

// clientOf достаёт данные клиента заказа. Клиента может не быть —
// тогда в документе останется только то, что известно по заказу.
func (a *API) clientOf(r *http.Request, order store.Order) store.Client {
	if order.ClientID == nil {
		return store.Client{Name: order.ClientName}
	}
	client, err := a.store.Client(r.Context(), *order.ClientID)
	if err != nil {
		return store.Client{Name: order.ClientName}
	}
	return client
}

func buildDocument(
	company store.Company,
	order store.Order,
	items []store.OrderItem,
	doc store.Document,
	client store.Client,
) documents.Data {
	lines := make([]documents.Line, 0, len(items))
	var total int64
	for i, item := range items {
		lines = append(lines, documents.Line{
			Number:   i + 1,
			Name:     item.Name,
			Quantity: documents.Quantity(item.QtyMilli),
			Unit:     item.Unit,
			Price:    documents.Money(item.PriceKop),
			Total:    documents.Money(item.TotalKop),
		})
		total += item.TotalKop
	}

	// У заказа без состава печатаем одну строку с его названием и ценой:
	// пустая таблица в счёте выглядит как ошибка, а работа-то была.
	if len(lines) == 0 {
		lines = append(lines, documents.Line{
			Number:   1,
			Name:     order.Title,
			Quantity: "1",
			Unit:     "усл.",
			Price:    documents.Money(order.PriceKop),
			Total:    documents.Money(order.PriceKop),
		})
		total = order.PriceKop
	}

	title := "Счёт на оплату"
	if doc.Kind == store.DocAct {
		title = "Акт выполненных работ"
	}

	return documents.Data{
		Kind:      doc.Kind,
		Title:     title,
		Number:    doc.Number,
		Year:      doc.Year,
		IssuedAt:  documents.FormatDate(doc.IssuedAt),
		OrderNo:   order.ID,
		OrderName: order.Title,

		Supplier: documents.Party{
			Name:    firstNonEmpty(company.FullName, company.ShortName),
			INN:     company.INN,
			OGRNIP:  company.OGRNIP,
			Address: company.Address,
			Phone:   company.Phone,
			Email:   company.Email,
		},
		Customer: documents.Party{
			Name:    firstNonEmpty(client.Name, order.ClientName),
			Address: client.Address,
			Phone:   client.Phone,
			Email:   client.Email,
		},
		Bank: documents.Bank{
			Name:        company.BankName,
			BIK:         company.BankBIK,
			Account:     company.BankAccount,
			CorrAccount: company.BankCorrAccount,
		},

		Lines:      lines,
		Total:      documents.Money(total),
		TotalWords: documents.MoneyWords(total),
		ItemsCount: len(lines),
		ItemsWord:  documents.ItemsWord(len(lines)),

		TaxNote:    company.TaxNote,
		FooterNote: company.FooterNote,

		SignerName:  firstNonEmpty(company.SignerName, company.ShortName),
		SignerTitle: company.SignerTitle,
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if trim(v) != "" {
			return v
		}
	}
	return ""
}
