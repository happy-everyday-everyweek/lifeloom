package dev.lifeloom.core;

/**
 * 用户提示通道（由外壳注入）。
 *
 * <p>供系统插件向用户展示提示与询问是/否；缺省实现为“全部拒绝”，
 * 未接入外壳时不会误放行。
 */
public interface UserPrompt {

    /** 缺省提示通道：不展示、不询问、一律拒绝。 */
    UserPrompt DENY_ALL = new UserPrompt() {
        @Override
        public void inform(String message) {
        }

        @Override
        public boolean confirm(String message) {
            return false;
        }
    };

    /** 展示一条提示信息。 */
    void inform(String message);

    /** 询问一个是/否问题；返回用户是否同意。 */
    boolean confirm(String message);
}