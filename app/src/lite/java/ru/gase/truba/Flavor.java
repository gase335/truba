package ru.gase.truba;

/** Возможности сборки: lite. */
final class Flavor {
    private Flavor() {}

    /** Версия для Android 7 («Трубочка»): без фонового режима и картинки-в-картинке. */
    static final boolean LITE = true;
    static final boolean BACKGROUND = !LITE;
    static final boolean PIP = !LITE;
}
