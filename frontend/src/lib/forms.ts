/** The text value of a form field (FormData entries can also be files), or "". */
export const formText = (form: FormData, name: string): string => {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
};
