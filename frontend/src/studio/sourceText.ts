// Monaco может хранить CRLF и скрывать начальный BOM. Сравниваем содержание,
// не технический способ хранения строк; исходный файл при этом не меняем.
export function editorText(text: string) {
  return text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
}
