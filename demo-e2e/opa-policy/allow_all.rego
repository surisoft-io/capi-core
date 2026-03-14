package capi.allow_all

default allow = false

allow if {
    [_, payload, _] := io.jwt.decode(input.token)
    payload.realm_access.roles[_] == "admin"
}

allow if {
    [_, payload, _] := io.jwt.decode(input.token)
    payload.realm_access.roles[_] == "manager"
}

allow if {
    [_, payload, _] := io.jwt.decode(input.token)
    payload.realm_access.roles[_] == "viewer"
}
