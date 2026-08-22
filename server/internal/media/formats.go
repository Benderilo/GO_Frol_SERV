package media

// Регистрируем декодеры форматов: image.Decode и image.DecodeConfig
// узнают формат только по зарегистрированным пакетам.
import (
	_ "image/jpeg"
	_ "image/png"

	_ "golang.org/x/image/webp"
)
