#!/usr/bin/env python3
"""Pruebas de firestore.rules contra los emuladores de Firebase (Auth :9199, Firestore :8185).

Sin dependencias: usa el emulador de Auth para obtener ID tokens reales y la API REST de
Firestore, donde las reglas se aplican igual que desde la app. Uso: scripts/test-rules.sh
"""
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

PROJECT = "demo-toka"
AUTH = "http://127.0.0.1:9199/identitytoolkit.googleapis.com/v1"
FS = f"http://127.0.0.1:8185/v1/projects/{PROJECT}/databases/(default)/documents"
ROOT = f"projects/{PROJECT}/databases/(default)/documents"


# ── Valores de Firestore ─────────────────────────────────────────────────────

def val(v):
    if v is None:
        return {"nullValue": None}
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, str):
        return {"stringValue": v}
    if isinstance(v, datetime):
        return {"timestampValue": v.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    if isinstance(v, list):
        return {"arrayValue": {"values": [val(x) for x in v]}}
    if isinstance(v, dict):
        return {"mapValue": {"fields": {k: val(x) for k, x in v.items()}}}
    raise TypeError(v)


def fields(d):
    return {k: val(v) for k, v in d.items()}


def http(method, url, token=None, body=None):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


# ── Usuarios y operaciones ───────────────────────────────────────────────────

class User:
    def __init__(self, name):
        s, r = http("POST", f"{AUTH}/accounts:signUp?key=fake",
                    body={"email": f"{name}-{uuid.uuid4().hex[:6]}@test.cl", "password": "secret1234",
                          "returnSecureToken": True})
        assert s == 200, r
        self.token, self.uid = r["idToken"], r["localId"]

    def commit(self, *writes):
        """Lote atómico (como WriteBatch). Devuelve True si las reglas lo permitieron."""
        s, r = http("POST", f"{FS}:commit", self.token, {"writes": list(writes)})
        self.last_error = None if s == 200 else r
        return s == 200

    def read(self, path):
        s, _ = http("GET", f"{FS}/{path}", self.token)
        return s == 200

    def list(self, path):
        s, _ = http("GET", f"{FS}/{path}", self.token)
        return s == 200


def doc(path, data, mask=None):
    w = {"update": {"name": f"{ROOT}/{path}", "fields": fields(data)}}
    if mask is not None:
        w["updateMask"] = {"fieldPaths": mask}
    return w


def delete(path):
    return {"delete": f"{ROOT}/{path}"}


NOW = datetime.now(timezone.utc)
DUE = NOW + timedelta(days=3)

passed = failed = 0


def check(name, got, want=True):
    global passed, failed
    if got == want:
        passed += 1
        print(f"  PASS {name}")
    else:
        failed += 1
        print(f"  FAIL {name} (esperado {'permitido' if want else 'denegado'})")


def new_house(owner, code=None):
    hid = "h" + uuid.uuid4().hex[:8]
    code = code or "".join(c for c in uuid.uuid4().hex.upper() if c.isalnum())[:6]
    ok = owner.commit(
        doc(f"households/{hid}", {"inviteCode": code, "members": [owner.uid], "createdBy": owner.uid, "createdAt": NOW}),
        doc(f"invites/{code}", {"householdId": hid}),
        doc(f"households/{hid}/people/{owner.uid}", {"name": "Ana", "color": "#a78bfa", "emoji": "🐱"}),
        doc(f"users/{owner.uid}", {"householdId": hid}),
    )
    assert ok, f"no se pudo crear el hogar de prueba: {owner.last_error}"
    return hid, code


def task(**kw):
    d = {"templateId": "t1", "templateName": "Basura", "status": "pending", "dueAt": DUE}
    d.update(kw)
    return d


def main():
    ana, beto, eva = User("ana"), User("beto"), User("eva")

    print("Hogar e invitaciones")
    hid = "h" + uuid.uuid4().hex[:8]
    check("no se puede crear un hogar con otro miembro", ana.commit(doc(f"households/{hid}", {
        "inviteCode": "ABC123", "members": [beto.uid], "createdBy": ana.uid, "createdAt": NOW})), False)
    check("no se puede crear un hogar con campos extra", ana.commit(doc(f"households/{hid}", {
        "inviteCode": "ABC123", "members": [ana.uid], "createdBy": ana.uid, "createdAt": NOW, "admin": True})), False)
    hid, code = new_house(ana)
    check("crear hogar + invitación + perfil", True)
    check("un miembro lee su hogar", ana.read(f"households/{hid}"))
    check("un extraño NO lee el hogar", beto.read(f"households/{hid}"), False)
    check("un extraño NO lista las tareas", beto.list(f"households/{hid}/tasks"), False)
    check("se puede consultar una invitación puntual", beto.read(f"invites/{code}"))
    check("no se pueden listar las invitaciones", beto.list("invites"), False)
    check("invitación para un hogar ajeno denegada", beto.commit(doc("invites/ZZZ999", {"householdId": hid})), False)
    check("el puntero de usuario es solo del dueño", beto.read(f"users/{ana.uid}"), False)

    print("Unirse y salir")
    check("unirse agregándose a uno mismo", beto.commit(
        doc(f"households/{hid}", {"members": [ana.uid, beto.uid]}, ["members"]),
        doc(f"households/{hid}/people/{beto.uid}", {"name": "Beto", "color": "#60a5fa", "emoji": "🐶"}),
        doc(f"users/{beto.uid}", {"householdId": hid}),
    ))
    check("no se puede meter a un tercero", eva.commit(
        doc(f"households/{hid}", {"members": [ana.uid, beto.uid, "otro"]}, ["members"])), False)
    check("no se puede expulsar a otro", beto.commit(
        doc(f"households/{hid}", {"members": [beto.uid]}, ["members"])), False)
    check("un extraño no cambia el código", eva.commit(
        doc(f"households/{hid}", {"inviteCode": "NEW111"}, ["inviteCode"])), False)
    check("un miembro regenera el código", beto.commit(
        doc(f"households/{hid}", {"inviteCode": "NEW111"}, ["inviteCode"]),
        doc("invites/NEW111", {"householdId": hid})))
    check("un miembro borra la invitación vieja", beto.commit(delete(f"invites/{code}")))
    check("un extraño no borra invitaciones", eva.commit(delete("invites/NEW111")), False)

    print("Perfiles")
    check("editar el propio perfil", ana.commit(
        doc(f"households/{hid}/people/{ana.uid}", {"name": "Ana P", "color": "#a78bfa", "emoji": "🦊"})))
    check("no se edita el perfil de otro", ana.commit(
        doc(f"households/{hid}/people/{beto.uid}", {"name": "Hackeado", "color": "#a78bfa", "emoji": "🦊"})), False)
    check("color inválido denegado", ana.commit(
        doc(f"households/{hid}/people/{ana.uid}", {"name": "Ana", "color": "rojo", "emoji": "🦊"})), False)
    check("nombre muy largo denegado", ana.commit(
        doc(f"households/{hid}/people/{ana.uid}", {"name": "x" * 41, "color": "#a78bfa", "emoji": "🦊"})), False)

    print("Plantillas")
    tpl = {"name": "Basura", "isActive": True, "recurrenceDays": 7, "preferredAssigneeId": beto.uid}
    check("crear plantilla válida", ana.commit(doc(f"households/{hid}/templates/t1", tpl)))
    check("un extraño no crea plantillas", eva.commit(doc(f"households/{hid}/templates/t9", tpl)), False)
    check("preferido de otro hogar denegado", ana.commit(
        doc(f"households/{hid}/templates/t2", {**tpl, "preferredAssigneeId": eva.uid})), False)
    check("recurrencia 0 denegada", ana.commit(doc(f"households/{hid}/templates/t3", {**tpl, "recurrenceDays": 0})), False)
    check("recurrencia 100000 denegada", ana.commit(doc(f"households/{hid}/templates/t3", {**tpl, "recurrenceDays": 100000})), False)
    check("nombre vacío denegado", ana.commit(doc(f"households/{hid}/templates/t3", {**tpl, "name": ""})), False)
    check("campo desconocido denegado", ana.commit(doc(f"households/{hid}/templates/t3", {**tpl, "x": 1})), False)
    check("sin recurrencia ni preferido (una sola vez)", ana.commit(
        doc(f"households/{hid}/templates/t4", {"name": "Una vez", "isActive": True, "recurrenceDays": None,
                                               "preferredAssigneeId": None})))
    check("las plantillas no se borran", ana.commit(delete(f"households/{hid}/templates/t1")), False)

    print("Tareas")
    check("crear tarea pendiente", ana.commit(doc(f"households/{hid}/tasks/i1", task(assignedToId=ana.uid))))
    check("un extraño no crea tareas", eva.commit(doc(f"households/{hid}/tasks/i9", task())), False)
    check("asignar a alguien de otro hogar denegado", ana.commit(
        doc(f"households/{hid}/tasks/i2", task(assignedToId=eva.uid))), False)
    check("pendiente con completedById denegado", ana.commit(
        doc(f"households/{hid}/tasks/i2", task(completedById=ana.uid, completedAt=NOW))), False)
    check("estado inválido denegado", ana.commit(doc(f"households/{hid}/tasks/i2", task(status="hecho"))), False)
    check("dueAt que no es fecha denegado", ana.commit(doc(f"households/{hid}/tasks/i2", task(dueAt="mañana"))), False)
    check("notas muy largas denegadas", ana.commit(doc(f"households/{hid}/tasks/i2", task(notes="n" * 2001))), False)
    check("completar atribuyéndose la tarea", ana.commit(
        doc(f"households/{hid}/tasks/i1", task(status="done", completedById=ana.uid, completedAt=NOW))))
    check("completar a nombre de otro denegado", ana.commit(
        doc(f"households/{hid}/tasks/i1", task(status="done", completedById=beto.uid, completedAt=NOW))), False)
    check("completar + crear la siguiente en un lote", ana.commit(
        doc(f"households/{hid}/tasks/i3", task()),
        doc(f"households/{hid}/tasks/i3_next", task(generatedFrom="i3")),
        doc(f"households/{hid}/tasks/i3", task(status="done", completedById=ana.uid, completedAt=NOW))))

    print("Deshacer y borrados")
    check("deshacer: solo toca los campos de resolución", ana.commit(
        doc(f"households/{hid}/tasks/i1", {"status": "pending", "completedById": None, "completedAt": None},
            ["status", "completedById", "completedAt"])))
    check("borrar una pendiente (la que generó el completado)", ana.commit(delete(f"households/{hid}/tasks/i3_next")))
    ana.commit(doc(f"households/{hid}/tasks/i4", task()),
               doc(f"households/{hid}/tasks/i4", task(status="done", completedById=ana.uid, completedAt=NOW)))
    check("no se borra el historial", ana.commit(delete(f"households/{hid}/tasks/i4")), False)
    check("un extraño no borra tareas", eva.commit(delete(f"households/{hid}/tasks/i1")), False)
    check("reenvío offline: recrear una 'siguiente' ya completada NO la resucita", ana.commit(
        doc(f"households/{hid}/tasks/i5_next", task(generatedFrom="i5")),
        doc(f"households/{hid}/tasks/i5_next", task(status="done", completedById=ana.uid, completedAt=NOW))) and
        not ana.commit(doc(f"households/{hid}/tasks/i5_next", task(generatedFrom="i5", createdAt=NOW))))

    print("Salir del hogar")
    check("quitarse a uno mismo", beto.commit(
        doc(f"households/{hid}", {"members": [ana.uid]}, ["members"]),
        delete(f"households/{hid}/people/{beto.uid}"),
        delete(f"users/{beto.uid}")))
    check("tras salir ya no lee el hogar", beto.read(f"households/{hid}"), False)

    print(f"\n{passed} pasaron, {failed} fallaron")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
