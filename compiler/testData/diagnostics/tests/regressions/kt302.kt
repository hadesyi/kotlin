// KT-302 Report an error when inheriting many implementations of the same member

package kt302

interface A {
    <!REDUNDANT_MODIFIER_CONTAINING_DECLARATION!>open<!> fun foo() {}
}

interface B {
    <!REDUNDANT_MODIFIER_CONTAINING_DECLARATION!>open<!> fun foo() {}
}

<!MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED!>class C<!> : A, B {} //should be error here