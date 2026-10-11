-- Vínculo con el usuario de inicio de sesión (claim "sub" del token). Permite a los BFF resolver "mi cliente"
-- y autorizar por propiedad del recurso (cada cliente solo ve sus cuentas y pagos).
ALTER TABLE customer ADD COLUMN username VARCHAR(60) UNIQUE;
