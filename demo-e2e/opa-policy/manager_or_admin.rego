package capi.manager_or_admin

default allow = false

allow if {
    [_, payload, _] := io.jwt.decode(input.token)
    payload.realm_access.roles[_] == "admin"
}

allow if {
    [_, payload, _] := io.jwt.decode(input.token)
    payload.realm_access.roles[_] == "manager"
}
