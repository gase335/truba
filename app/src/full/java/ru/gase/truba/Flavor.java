package ru.gase.truba;

/** Возможности сборки: full. */
final class Flavor {
    private Flavor() {}

    /** Версия для Android 7 («Трубочка»): без фонового режима и картинки-в-картинке. */
    static final boolean LITE = false;
    static final boolean BACKGROUND = !LITE;
    static final boolean PIP = !LITE;
}
