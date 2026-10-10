package store

import (
	"context"
	"fmt"
	"regexp"
	"strconv"
)

// HasGeo — есть ли точка на карте. 0, 0 значит «нет»: в Атлантике
// электрику делать нечего, а отдельный признак пришлось бы держать
// в согласии с координатами.
func HasGeo(lat, lon float64) bool {
	return (lat != 0 || lon != 0) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
}

// cleanGeo — координаты для записи в базу: вне диапазона значит «точки нет».
func cleanGeo(lat, lon float64) (float64, float64) {
	if !HasGeo(lat, lon) {
		return 0, 0
	}
	return lat, lon
}

// GeoMapURL — ссылка на точку в Яндекс Картах: открывается и в браузере,
// и в приложении карт. Порядок в pt — долгота, широта.
func GeoMapURL(lat, lon float64) string {
	return fmt.Sprintf("https://yandex.ru/maps/?pt=%.6f,%.6f&z=17&l=map", lon, lat)
}

// legacyGeo — как бот раньше записывал геопозицию прямо в адрес:
// «геопозиция 51.533103, 46.034158 — https://yandex.ru/maps/...».
var legacyGeo = regexp.MustCompile(`^геопозиция (-?\d+(?:\.\d+)?), (-?\d+(?:\.\d+)?)(?: — \S+)?$`)

// parseLegacyGeo достаёт координаты из такого адреса.
func parseLegacyGeo(address string) (lat, lon float64, ok bool) {
	m := legacyGeo.FindStringSubmatch(address)
	if m == nil {
		return 0, 0, false
	}
	lat, err1 := strconv.ParseFloat(m[1], 64)
	lon, err2 := strconv.ParseFloat(m[2], 64)
	if err1 != nil || err2 != nil || !HasGeo(lat, lon) {
		return 0, 0, false
	}
	return lat, lon, true
}

// backfillGeo переносит геопозицию, которую бот раньше писал текстом
// в адрес, в отдельные координаты: адрес освобождается под улицу и дом,
// а маршрут строится по точке.
//
// Идемпотентно: трогает только строки без координат с адресом старого вида.
func (s *Store) backfillGeo(ctx context.Context) error {
	for _, table := range []string{"clients", "requests", "max_dialogs"} {
		key := "id"
		if table == "max_dialogs" {
			key = "user_id"
		}
		rows, err := s.db.QueryContext(ctx, fmt.Sprintf(
			`SELECT %s, address FROM %s WHERE lat = 0 AND lon = 0 AND address LIKE 'геопозиция %%'`, key, table))
		if err != nil {
			return fmt.Errorf("перенос геопозиции (%s): %w", table, err)
		}
		type fix struct {
			id       int64
			lat, lon float64
		}
		var fixes []fix
		for rows.Next() {
			var id int64
			var address string
			if err := rows.Scan(&id, &address); err != nil {
				rows.Close()
				return err
			}
			if lat, lon, ok := parseLegacyGeo(address); ok {
				fixes = append(fixes, fix{id, lat, lon})
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, f := range fixes {
			if _, err := s.db.ExecContext(ctx, fmt.Sprintf(
				`UPDATE %s SET address = '', lat = ?, lon = ? WHERE %s = ?`, table, key),
				f.lat, f.lon, f.id); err != nil {
				return fmt.Errorf("перенос геопозиции (%s): %w", table, err)
			}
		}
	}

	// Заказы, заведённые по заявкам до появления адреса у заказа, берут
	// адрес и точку из своей заявки.
	if _, err := s.db.ExecContext(ctx,
		`UPDATE orders SET
		    address = COALESCE((SELECT r.address FROM requests r WHERE r.order_id = orders.id
		                         ORDER BY r.id DESC LIMIT 1), ''),
		    lat = COALESCE((SELECT r.lat FROM requests r WHERE r.order_id = orders.id
		                     ORDER BY r.id DESC LIMIT 1), 0),
		    lon = COALESCE((SELECT r.lon FROM requests r WHERE r.order_id = orders.id
		                     ORDER BY r.id DESC LIMIT 1), 0)
		 WHERE address = '' AND lat = 0 AND lon = 0
		   AND EXISTS (SELECT 1 FROM requests r WHERE r.order_id = orders.id
		                AND (r.address != '' OR r.lat != 0 OR r.lon != 0))`); err != nil {
		return fmt.Errorf("перенос адреса в заказы: %w", err)
	}
	return nil
}
