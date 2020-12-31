/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
#include <truffleruby-impl.h>

// Object and Kernel, rb_obj_*

// Type checks

int rb_type(VALUE value) {
  return polyglot_as_i32(RUBY_CEXT_INVOKE_NO_WRAP("rb_type", value));
}

bool RB_TYPE_P(VALUE value, int type) {
  if (value == Qundef) {
    return 0;
  }

  // Ripper uses RB_TYPE_P to check NODE* values for T_NODE
  if (type == T_NODE && rb_tr_is_native_object(value)) {
    return RB_BUILTIN_TYPE_NATIVE(value) == type;
  }

  return polyglot_as_boolean(polyglot_invoke(RUBY_CEXT, "RB_TYPE_P", rb_tr_unwrap(value), type));
}

int rb_special_const_p(VALUE object) {
  // Ripper calls this from add_mark_object
  // Cannot unwrap a natively-allocated NODE*
  if (rb_tr_is_native_object(object)) {
    return 0;
  }

  return polyglot_as_boolean(RUBY_CEXT_INVOKE_NO_WRAP("rb_special_const_p", object));
}

void rb_check_type(VALUE value, int type) {
  polyglot_invoke(RUBY_CEXT, "rb_check_type", rb_tr_unwrap(value), type);
}

VALUE rb_obj_is_instance_of(VALUE object, VALUE ruby_class) {
  return RUBY_CEXT_INVOKE("rb_obj_is_instance_of", object, ruby_class);
}

VALUE rb_obj_is_kind_of(VALUE object, VALUE ruby_class) {
  return RUBY_CEXT_INVOKE("rb_obj_is_kind_of", object, ruby_class);
}

VALUE rb_check_convert_type(VALUE val, int type, const char *type_name, const char *method) {
  return RUBY_CEXT_INVOKE("rb_check_convert_type", val, rb_str_new_cstr(type_name), rb_str_new_cstr(method));
}

VALUE rb_convert_type(VALUE object, int type, const char *type_name, const char *method) {
  return RUBY_CEXT_INVOKE("rb_convert_type", object, rb_str_new_cstr(type_name), rb_str_new_cstr(method));
}

VALUE rb_check_array_type(VALUE array) {
  return rb_check_convert_type(array, T_ARRAY, "Array", "to_ary");
}

VALUE rb_check_hash_type(VALUE hash) {
  return rb_check_convert_type(hash, T_HASH, "Hash", "to_hash");
}

VALUE rb_check_string_type(VALUE object) {
  return rb_check_convert_type(object, T_STRING, "String", "to_str");
}

// #to_s, #inspect, #p

VALUE rb_obj_as_string(VALUE object) {
  return RUBY_CEXT_INVOKE("rb_obj_as_string", object);
}

VALUE rb_any_to_s(VALUE object) {
  return RUBY_CEXT_INVOKE("rb_any_to_s", object);
}

VALUE rb_inspect(VALUE object) {
  return RUBY_CEXT_INVOKE("rb_inspect", object);
}

void rb_p(VALUE obj) {
  RUBY_INVOKE_NO_WRAP(rb_mKernel, "p", obj);
}

// rb_obj_*

VALUE rb_obj_hide(VALUE obj) {
  // In MRI, this deletes the class information which is later set by rb_obj_reveal.
  // It also hides the object from each_object, we do not hide it.
  return obj;
}

VALUE rb_obj_reveal(VALUE obj, VALUE klass) {
  // In MRI, this sets the class of the object, we are not deleting the class in rb_obj_hide, so we
  // ensure that class matches.
  return RUBY_CEXT_INVOKE("ensure_class", obj, klass,
             rb_str_new_cstr("class %s supplied to rb_obj_reveal does not matches the obj's class %s"));
  return obj;
}

VALUE rb_obj_clone(VALUE obj) {
  return rb_funcall(obj, rb_intern("clone"), 0);
}

VALUE rb_obj_dup(VALUE object) {
  return RUBY_INVOKE(object, "dup");
}

VALUE rb_obj_id(VALUE object) {
  return RUBY_INVOKE(object, "object_id");
}

// The semantics of SameOrEqualNode: a.equal?(b) || a == b
VALUE rb_equal(VALUE a, VALUE b) {
  return RUBY_CEXT_INVOKE("rb_equal", a, b);
}

void rb_obj_call_init(VALUE object, int argc, const VALUE *argv) {
  RUBY_CEXT_INVOKE_NO_WRAP("rb_obj_call_init", object, rb_ary_new4(argc, argv));
}

const char *rb_obj_classname(VALUE object) {
  VALUE str = RUBY_CEXT_INVOKE("rb_obj_classname", object);
  if (str != Qnil) {
    return RSTRING_PTR(str);
  } else {
    return NULL;
  }
}

VALUE rb_obj_method(VALUE obj, VALUE vid) {
  return RUBY_INVOKE(obj, "method", ID2SYM(rb_intern_str(vid)));
}

// taint status

VALUE rb_obj_tainted(VALUE obj) {
  return rb_funcall(obj, rb_intern("tainted?"), 0);
}

VALUE rb_obj_taint(VALUE object) {
  return RUBY_INVOKE(object, "taint");
}

VALUE rb_obj_untaint(VALUE obj) {
  return rb_funcall(obj, rb_intern("untaint"), 0);
}

VALUE rb_obj_untrusted(VALUE obj) {
  return rb_funcall(obj, rb_intern("untrusted?"), 0);
}

VALUE rb_obj_trust(VALUE obj) {
  return rb_funcall(obj, rb_intern("trust"), 0);
}

VALUE rb_obj_untrust(VALUE obj) {
  return rb_funcall(obj, rb_intern("untrust"), 0);
}

bool rb_tr_obj_taintable_p(VALUE object) {
  return polyglot_as_boolean(RUBY_CEXT_INVOKE_NO_WRAP("RB_OBJ_TAINTABLE", object));
}

bool rb_tr_obj_tainted_p(VALUE object) {
  return RTEST(rb_obj_tainted(object));
}

void rb_tr_obj_infect(VALUE a, VALUE b) {
  rb_warning("rb_obj_infect is deprecated and will be removed in Ruby 3.2.");
}

// frozen status

VALUE rb_obj_frozen_p(VALUE object) {
  return RUBY_INVOKE(object, "frozen?");
}

VALUE rb_obj_freeze(VALUE object) {
  return RUBY_CEXT_INVOKE("rb_obj_freeze", object);
}

void rb_check_frozen(VALUE object) {
  RUBY_CEXT_INVOKE_NO_WRAP("rb_check_frozen", object);
}

// require

VALUE rb_require(const char *feature) {
  return RUBY_CEXT_INVOKE("rb_require", rb_str_new_cstr(feature));
}

// at_exit

void rb_set_end_proc(void (*func)(VALUE), VALUE data) {
  polyglot_invoke(RUBY_CEXT, "rb_set_end_proc", func, data);
}

// eval

VALUE rb_eval_string(const char *str) {
  return RUBY_CEXT_INVOKE("rb_eval_string", rb_str_new_cstr(str));
}

VALUE rb_obj_instance_eval(int argc, const VALUE *argv, VALUE self) {
  return RUBY_CEXT_INVOKE("rb_obj_instance_eval", self, rb_ary_new4(argc, argv), rb_block_proc());
}

VALUE rb_eval_string_protect(const char *str, int *state) {
  return rb_protect((VALUE (*)(VALUE))rb_eval_string, (VALUE)str, state);
}
