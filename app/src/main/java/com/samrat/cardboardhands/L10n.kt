package com.samrat.cardboardhands

import android.content.Context

/**
 * PhoneXR's languages. The interface is written in English; [tr] gives the chosen language's text
 * for an English source string (untranslated strings stay English).
 */
object L10n {
    enum class Lang(val code: String, val title: String, val speech: String) {
        EN("en", "English", "en-US"),
        RU("ru", "Русский", "ru-RU"),
        PT_BR("pt-BR", "Português (Brasil)", "pt-BR"),
        PT_PT("pt-PT", "Português (Portugal)", "pt-PT"),
    }

    private const val PREFS = "language"
    @Volatile var current = Lang.EN
        private set

    fun init(context: Context) {
        current = runCatching { Lang.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", Lang.EN.name)!!) }
            .getOrDefault(Lang.EN)
    }

    fun set(context: Context, lang: Lang) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang.name).apply()
        current = lang
    }

    fun tr(en: String): String {
        val index = when (current) { Lang.EN -> return en; Lang.RU -> 0; Lang.PT_BR -> 1; Lang.PT_PT -> 2 }
        return DICT[en]?.get(index) ?: en
    }

    /** English → Russian, Portuguese (Brazil), Portuguese (Portugal). */
    private val DICT: Map<String, Array<String>> = mapOf(
        // Tabs and main screens
        "Menu" to arrayOf("Меню", "Menu", "Menu"),
        "Store" to arrayOf("Магазин", "Loja", "Loja"),
        "Friends" to arrayOf("Друзья", "Amigos", "Amigos"),
        "Settings" to arrayOf("Настройки", "Ajustes", "Definições"),
        "Enter VR" to arrayOf("Войти в VR", "Entrar no VR", "Entrar em VR"),
        "Games" to arrayOf("Игры", "Jogos", "Jogos"),
        // VR home (Horizon library and dock)
        "Library" to arrayOf("Библиотека", "Biblioteca", "Biblioteca"),
        "Are you ready?" to arrayOf("Вы готовы?", "Está pronto?", "Está pronto?"),
        "Feed" to arrayOf("Лента", "Feed", "Feed"),
        "Friends online" to arrayOf("Друзья в сети", "Amigos online", "Amigos online"),
        "What's new" to arrayOf("Новое", "Novidades", "Novidades"),
        "Try these" to arrayOf("Попробуйте", "Experimente", "Experimente"),
        "Finger touch" to arrayOf("Касание пальцем", "Toque com o dedo", "Toque com o dedo"),
        "See your room" to arrayOf("Видеть комнату", "Ver o ambiente", "Ver o espaço"),
        "Mic off" to arrayOf("Микрофон выкл.", "Microfone desligado", "Microfone desligado"),
        "Screenshot" to arrayOf("Скриншот", "Captura", "Captura"),
        "Record video" to arrayOf("Снять видео", "Record video", "Record video"),
        "Stop recording" to arrayOf("Остановить запись", "Stop recording", "Stop recording"),
        "Video saved to Photos" to arrayOf("Видео сохранено в «Фото»", "Video saved to Photos", "Video saved to Photos"),
        "Couldn't save the video" to arrayOf(
            "Не удалось сохранить видео",
            "Couldn't save the video",
            "Couldn't save the video"),
        "Couldn't start recording" to arrayOf(
            "Не удалось начать запись",
            "Couldn't start recording",
            "Couldn't start recording"),
        "Recording" to arrayOf("Запись началась", "Recording", "Recording"),
        "Mirror" to arrayOf("Зеркало", "Espelho", "Espelho"),
        "Full body" to arrayOf("Полное тело", "Corpo inteiro", "Corpo inteiro"),
        "Head, arms and chest" to arrayOf("Голова, руки и торс", "Cabeça, braços e tronco", "Cabeça, braços e tronco"),
        "In VR" to arrayOf("В VR", "No VR", "Em VR"),
        "Watching together" to arrayOf("Смотрит вместе", "Assistindo juntos", "A ver juntos"),
        "Join" to arrayOf("Присоединиться", "Entrar", "Juntar-se"),
        "Watch together" to arrayOf("Смотреть вместе", "Assistir juntos", "Ver juntos"),
        "Together" to arrayOf("Вместе", "Juntos", "Juntos"),
        "Make in VRoid" to arrayOf("Сделать в VRoid", "Criar no VRoid", "Criar no VRoid"),
        "Make a character in VRoid, export it as .vrm and tap “Load a .glb / .vrm file”" to arrayOf(
            "Сделайте персонажа в VRoid, экспортируйте .vrm и нажмите «Загрузить файл .glb / .vrm»",
            "Crie um personagem no VRoid, exporte como .vrm e toque em “Carregar arquivo .glb / .vrm”",
            "Crie uma personagem no VRoid, exporte como .vrm e toque em “Carregar ficheiro .glb / .vrm”"),
        "Choose your headset" to arrayOf("Выберите шлем", "Escolha o headset", "Escolha o headset"),
        "Other headset" to arrayOf("Другой шлем", "Outro headset", "Outro headset"),
        "Lenses" to arrayOf("Линзы", "Lentes", "Lentes"),
        "mm" to arrayOf("мм", "mm", "mm"),
        "Yes" to arrayOf("Да", "Sim", "Sim"),
        "Put the phone in the VR headset" to arrayOf(
            "Вставьте телефон в VR‑шлем",
            "Coloque o celular no headset VR",
            "Coloque o telemóvel no headset VR"),
        "PhoneXR account" to arrayOf("Аккаунт PhoneXR", "Conta PhoneXR", "Conta PhoneXR"),
        "Sign in to call friends, see them in VR and keep your settings. You can also do it later in the phone app." to arrayOf(
            "Войдите, чтобы звонить друзьям, видеть их в VR и сохранять настройки. Можно и позже — в приложении на телефоне.",
            "Entre para ligar para amigos, vê-los em VR e salvar suas configurações. Você também pode fazer isso depois no app do celular.",
            "Inicie sessão para ligar a amigos, vê-los em VR e guardar as definições. Também pode fazê-lo mais tarde na app do telemóvel."),
        "Create account" to arrayOf("Создать аккаунт", "Criar conta", "Criar conta"),
        "Sign in" to arrayOf("Вход", "Entrar", "Iniciar sessão"),
        "E-mail" to arrayOf("Почта", "E-mail", "E-mail"),
        "Password" to arrayOf("Пароль", "Senha", "Palavra-passe"),
        "Name" to arrayOf("Имя", "Nome", "Nome"),
        "Create" to arrayOf("Создать", "Criar", "Criar"),
        "Enter your e-mail and a password (6+ characters)" to arrayOf(
            "Введите почту и пароль (не короче 6 символов)",
            "Digite seu e-mail e uma senha (6+ caracteres)",
            "Introduza o e-mail e uma palavra-passe (6+ caracteres)"),
        "Enter your name" to arrayOf("Введите имя", "Digite seu nome", "Introduza o seu nome"),
        "Welcome" to arrayOf("Добро пожаловать", "Bem-vindo", "Bem-vindo"),
        "Room scan" to arrayOf("Сканирование комнаты", "Escaneamento do ambiente", "Digitalização da sala"),
        "Look at the floor, the walls and the table — PhoneXR covers them with a grid and remembers where the table is: the keyboard will lie on it. Then walk around the edge of the free space; the loop closes by itself, or make a fist." to arrayOf(
            "Осмотрите пол, стены и стол — PhoneXR покроет их сеткой и запомнит, где стоит стол: на нём будет клавиатура. Потом обойдите свободное место по краю; круг замкнётся сам, или сожмите кулак.",
            "Olhe para o chão, as paredes e a mesa — o PhoneXR os cobre com uma grade e lembra onde fica a mesa: o teclado ficará nela. Depois contorne o espaço livre; o círculo fecha sozinho, ou feche a mão.",
            "Olhe para o chão, as paredes e a mesa — o PhoneXR cobre-os com uma grelha e lembra-se de onde está a mesa: o teclado ficará nela. Depois contorne o espaço livre; o círculo fecha-se sozinho, ou feche a mão."),
        "Table found ✓" to arrayOf("Стол найден ✓", "Mesa encontrada ✓", "Mesa encontrada ✓"),
        "Looking for a table…" to arrayOf("Ищу стол…", "Procurando uma mesa…", "À procura de uma mesa…"),
        "The app and VR look the same; the theme is shared with the VR home." to arrayOf(
            "Приложение и VR выглядят одинаково; тема общая с VR‑домом.",
            "O app e o VR têm o mesmo visual; o tema é compartilhado com a casa VR.",
            "A app e o VR têm o mesmo aspeto; o tema é partilhado com a casa VR."),
        "All" to arrayOf("Все", "Todos", "Todos"),
        "Apps" to arrayOf("Приложения", "Apps", "Aplicações"),
        "Web" to arrayOf("Веб", "Web", "Web"),
        "People" to arrayOf("Люди", "Pessoas", "Pessoas"),
        "Worlds" to arrayOf("Миры", "Mundos", "Mundos"),
        "Recent" to arrayOf("Недавние", "Recentes", "Recentes"),
        "Search" to arrayOf("Поиск", "Pesquisar", "Pesquisar"),
        "Appearance" to arrayOf("Оформление", "Aparência", "Aspeto"),
        "Light" to arrayOf("Светлое", "Claro", "Claro"),
        "Dark" to arrayOf("Тёмное", "Escuro", "Escuro"),
        "Loading the store…" to arrayOf("Магазин загружается…", "Carregando a loja…", "A carregar a loja…"),
        "Your friends will be here — sign in in the PhoneXR app" to arrayOf(
            "Здесь будут друзья — войдите в аккаунт в приложении PhoneXR",
            "Seus amigos aparecerão aqui — entre na sua conta no app PhoneXR",
            "Os seus amigos aparecerão aqui — inicie sessão na app PhoneXR"),
        "VR games prepared in the PhoneXR app will be here" to arrayOf(
            "Здесь будут VR‑игры, подготовленные в приложении PhoneXR",
            "Os jogos VR preparados no app PhoneXR aparecerão aqui",
            "Os jogos VR preparados na app PhoneXR aparecerão aqui"),
        "Add web apps from the store" to arrayOf(
            "Добавьте веб‑приложения из магазина",
            "Adicione apps web da loja",
            "Adicione aplicações web da loja"),
        "Install" to arrayOf("Установка", "Instalação", "Instalação"),
        "Install a game from a file" to arrayOf(
            "Установить игру из файла",
            "Instalar jogo de um arquivo",
            "Instalar jogo a partir de ficheiro"),
        "Game store" to arrayOf("Магазин игр", "Loja de jogos", "Loja de jogos"),
        "Tracking" to arrayOf("Трекинг", "Rastreamento", "Rastreio"),
        "Stop tracking" to arrayOf("Остановить трекинг", "Parar rastreamento", "Parar rastreio"),
        "Controls" to arrayOf("Управление", "Controles", "Controlos"),
        "Controls and Joy‑Con" to arrayOf("Управление и Joy‑Con", "Controles e Joy‑Con", "Controlos e Joy‑Con"),
        "Joy‑Con via camera" to arrayOf("Joy‑Con через камеру", "Joy‑Con pela câmera", "Joy‑Con pela câmara"),
        "Checks" to arrayOf("Проверка", "Verificação", "Verificação"),
        "Test Joy‑Con gyro" to arrayOf(
            "Проверить гироскоп Joy‑Con",
            "Testar giroscópio do Joy‑Con",
            "Testar giroscópio do Joy‑Con"),
        "Account" to arrayOf("Аккаунт", "Conta", "Conta"),
        "Sign out" to arrayOf("Выйти", "Sair", "Terminar sessão"),
        "Language" to arrayOf("Язык", "Idioma", "Idioma"),
        // The very first card of the setup: the language itself
        "Choose your language" to arrayOf("Выберите язык", "Escolha o idioma", "Escolha o idioma"),
        "What's your name?" to arrayOf("Как вас зовут?", "Como você se chama?", "Como se chama?"),
        "Username" to arrayOf("Имя пользователя", "Nome de usuário", "Nome de utilizador"),
        "Touch" to arrayOf("Касание", "Toque", "Toque"),
        "Windows are pressed with a finger: point your index finger, curl the others, and push your hand forward briefly. Three times." to arrayOf(
            "Окна нажимаются пальцем: вытяните указательный палец, остальные согните, и коротко толкните руку вперёд. Три раза.",
            "As janelas são pressionadas com o dedo: aponte o indicador, dobre os outros e empurre a mão para a frente. Três vezes.",
            "As janelas são premidas com o dedo: aponte o indicador, dobre os outros e empurre a mão para a frente. Três vezes."),
        "Software Update" to arrayOf("Обновление ПО", "Atualização de Software", "Atualização de software"),
        "About" to arrayOf("О приложении", "Sobre", "Acerca de"),
        "Server" to arrayOf("Сервер", "Servidor", "Servidor"),
        "Refresh" to arrayOf("Обновить", "Atualizar", "Atualizar"),
        "Refreshing…" to arrayOf("Обновление…", "Atualizando…", "A atualizar…"),
        "Get" to arrayOf("Загрузить", "Obter", "Obter"),
        "Download" to arrayOf("Скачать", "Baixar", "Transferir"),
        "Play" to arrayOf("Играть", "Jogar", "Jogar"),
        "Open" to arrayOf("Открыть", "Abrir", "Abrir"),
        "Remove" to arrayOf("Удалить", "Remover", "Remover"),
        "Add" to arrayOf("Добавить", "Adicionar", "Adicionar"),
        "Close" to arrayOf("Закрыть", "Fechar", "Fechar"),
        "Done" to arrayOf("Готово", "Concluído", "Concluído"),
        "Cancel" to arrayOf("Отмена", "Cancelar", "Cancelar"),
        "Later" to arrayOf("Позже", "Depois", "Mais tarde"),
        "Details" to arrayOf("Подробнее", "Detalhes", "Detalhes"),
        "Skip" to arrayOf("Пропустить", "Pular", "Saltar"),
        "Continue" to arrayOf("Продолжить", "Continuar", "Continuar"),
        "Something went wrong" to arrayOf("Не получилось", "Não deu certo", "Não foi possível"),
        "VR modes" to arrayOf("VR‑режимы", "Modos VR", "Modos VR"),
        "PhoneXR apps" to arrayOf("Приложения PhoneXR", "Apps PhoneXR", "Apps PhoneXR"),
        "Android apps" to arrayOf("Android‑приложения", "Apps Android", "Apps Android"),
        "Web apps" to arrayOf("Веб‑приложения", "Apps web", "Apps web"),
        "Minecraft mods" to arrayOf("Моды Minecraft", "Mods do Minecraft", "Mods do Minecraft"),
        "Install a mod from a file" to arrayOf(
            "Установить мод из файла",
            "Instalar mod de um arquivo",
            "Instalar mod a partir de ficheiro"),
        "Install mod" to arrayOf("Установить мод", "Instalar mod", "Instalar mod"),
        "Nothing here yet" to arrayOf("Пока пусто", "Nada aqui ainda", "Ainda vazio"),
        "Loading…" to arrayOf("Загрузка…", "Carregando…", "A carregar…"),
        // VR home
        "Browser" to arrayOf("Браузер", "Navegador", "Navegador"),
        "Photos" to arrayOf("Фото", "Fotos", "Fotografias"),
        "Calls" to arrayOf("Звонки", "Chamadas", "Chamadas"),
        "Home" to arrayOf("Главная", "Início", "Início"),
        "Take photo" to arrayOf("Снять фото", "Tirar foto", "Tirar fotografia"),
        "Recenter" to arrayOf("Выровнять", "Recentralizar", "Recentrar"),
        "Boundary" to arrayOf("Граница", "Limite", "Limite"),
        "Exit VR" to arrayOf("Выйти из VR", "Sair do VR", "Sair de VR"),
        "Customize" to arrayOf("Настроить", "Personalizar", "Personalizar"),
        "About headset" to arrayOf("О гарнитуре", "Sobre o headset", "Sobre o headset"),
        "Persona" to arrayOf("Лицо", "Persona", "Persona"),
        "Add Persona" to arrayOf("Добавить лицо", "Adicionar Persona", "Adicionar Persona"),
        "Show Persona" to arrayOf("Показать лицо", "Mostrar Persona", "Mostrar Persona"),
        "Face scan" to arrayOf("Сканирование лица", "Escaneamento facial", "Digitalização facial"),
        "Start scanning" to arrayOf("Начать сканирование", "Iniciar escaneamento", "Iniciar digitalização"),
        "Scan with camera" to arrayOf("Сканировать камерой", "Escanear com a câmera", "Digitalizar com a câmara"),
        "Scan again" to arrayOf("Сканировать заново", "Escanear novamente", "Digitalizar novamente"),
        "Keep the phone in the headset · show your face to its camera" to arrayOf(
            "Не вынимайте телефон · покажите лицо камере шлема",
            "Mantenha o celular no headset · mostre o rosto à câmera",
            "Mantenha o telemóvel no headset · mostre o rosto à câmara"),
        "Look straight ahead" to arrayOf("Смотрите прямо", "Olhe para frente", "Olhe em frente"),
        "Slowly turn your head left" to arrayOf(
            "Медленно поверните голову влево",
            "Vire a cabeça devagar para a esquerda",
            "Rode lentamente a cabeça para a esquerda"),
        "Now turn your head right" to arrayOf(
            "Теперь поверните голову вправо",
            "Agora vire a cabeça para a direita",
            "Agora rode a cabeça para a direita"),
        "Raise your chin slightly" to arrayOf(
            "Слегка поднимите подбородок",
            "Levante um pouco o queixo",
            "Levante ligeiramente o queixo"),
        "Lower your chin slightly" to arrayOf(
            "Слегка опустите подбородок",
            "Abaixe um pouco o queixo",
            "Baixe ligeiramente o queixo"),
        "Face not visible" to arrayOf("Лицо не видно", "Rosto não visível", "Rosto não visível"),
        "Center your face in the frame" to arrayOf("Лицо в центр рамки", "Centralize o rosto", "Centre o rosto"),
        "Move your face closer to the headset camera" to arrayOf(
            "Приблизьте лицо к камере шлема",
            "Aproxime o rosto da câmera do headset",
            "Aproxime o rosto da câmara do headset"),
        "Headset camera unavailable" to arrayOf(
            "Камера шлема недоступна",
            "Câmera do headset indisponível",
            "Câmara do headset indisponível"),
        "Building your Persona…" to arrayOf("Создаю персону…", "Criando sua Persona…", "A criar a sua Persona…"),
        "Show your hands" to arrayOf("Покажите руки", "Mostre as mãos", "Mostre as mãos"),
        "Hold both hands in front of you with fingers open. Keep still for a few seconds." to arrayOf(
            "Держите обе руки перед собой, пальцы раскрыты. Не двигайтесь пару секунд.",
            "Mantenha as duas mãos à frente, com os dedos abertos. Fique parado por alguns segundos.",
            "Mantenha as duas mãos à frente, com os dedos abertos. Fique parado durante alguns segundos."),
        "Keep the phone in the headset. Show your face to its outward camera and slowly turn your head. The back is not needed." to arrayOf(
            "Не вынимайте телефон из шлема. Покажите лицо внешней камере и медленно поворачивайте голову. Затылок сканировать не нужно.",
            "Mantenha o celular no headset. Mostre o rosto à câmera externa e vire a cabeça devagar. Não é preciso mostrar a parte de trás.",
            "Mantenha o telemóvel no headset. Mostre o rosto à câmara externa e rode a cabeça devagar. Não é necessário mostrar a parte de trás."),
        "Follow the camera prompts: front, left, right, up and down." to arrayOf(
            "Следуйте подсказкам камеры: прямо, влево, вправо, вверх и вниз.",
            "Siga as instruções da câmera: frente, esquerda, direita, cima e baixo.",
            "Siga as instruções da câmara: frente, esquerda, direita, cima e baixo."),
        "Set up boundary" to arrayOf("Настроить границу", "Configurar limite", "Configurar limite"),
        "Remove boundary" to arrayOf("Удалить границу", "Remover limite", "Remover limite"),
        "Hand calibration" to arrayOf("Калибровка рук", "Calibração das mãos", "Calibração das mãos"),
        "Automatic Updates" to arrayOf("Автообновление", "Atualizações Automáticas", "Atualizações automáticas"),
        "Beta Updates" to arrayOf("Бета‑обновления", "Atualizações Beta", "Atualizações beta"),
        "Update Now" to arrayOf("Обновить сейчас", "Atualizar Agora", "Atualizar agora"),
        "PhoneXR is up to date" to arrayOf(
            "Установлена последняя версия",
            "O PhoneXR está atualizado",
            "O PhoneXR está atualizado"),
        // Calls and friends
        "Call" to arrayOf("Позвонить", "Ligar", "Ligar"),
        "Accept" to arrayOf("Принять", "Aceitar", "Aceitar"),
        "Decline" to arrayOf("Отклонить", "Recusar", "Recusar"),
        "End" to arrayOf("Завершить", "Encerrar", "Terminar"),
        "Mute" to arrayOf("Микрофон", "Microfone", "Microfone"),
        "Online" to arrayOf("В сети", "Online", "Online"),
        "Offline" to arrayOf("Не в сети", "Offline", "Offline"),
        "My friends" to arrayOf("Мои друзья", "Meus amigos", "Os meus amigos"),
        "Added you" to arrayOf("Добавили вас", "Adicionaram você", "Adicionaram-no"),
        "Find by username" to arrayOf("Найти по юзернейму", "Buscar por usuário", "Procurar por utilizador"),
        "Nobody is online" to arrayOf("Сейчас никого нет в сети", "Ninguém online agora", "Ninguém online agora"),
        // Elix
        "Ask Elix" to arrayOf("Спросите Elix", "Pergunte à Elix", "Pergunte à Elix"),
        "Listening…" to arrayOf("Слушаю…", "Ouvindo…", "A ouvir…"),
        "Thinking…" to arrayOf("Думаю…", "Pensando…", "A pensar…"),
        "Speak" to arrayOf("Говорить", "Falar", "Falar"),
        "Keyboard" to arrayOf("Клавиатура", "Teclado", "Teclado"),
        "Send" to arrayOf("Отправить", "Enviar", "Enviar"),
        "New chat" to arrayOf("Новый разговор", "Nova conversa", "Nova conversa"),
        "Hi" to arrayOf("Привет", "Oi", "Olá"),
        "How can I help?" to arrayOf("Чем помочь?", "Como posso ajudar?", "Como posso ajudar?"),
        "Great! Ready to help." to arrayOf(
            "Отлично! Готова помочь.",
            "Ótima! Pronta para ajudar.",
            "Ótima! Pronta para ajudar."),
        "You're welcome!" to arrayOf("Пожалуйста!", "De nada!", "De nada!"),
        "I'm Elix, the PhoneXR assistant." to arrayOf(
            "Я Elix, ассистент PhoneXR.",
            "Sou a Elix, a assistente do PhoneXR.",
            "Sou a Elix, a assistente do PhoneXR."),
        "It's" to arrayOf("Сейчас", "Agora são", "São"),
        "Battery" to arrayOf("Заряд", "Bateria", "Bateria"),
        "Taking a photo!" to arrayOf("Снимаю!", "Tirando foto!", "A tirar fotografia!"),
        "Done, view recentered." to arrayOf(
            "Готово, выровняла вид.",
            "Pronto, visão recentralizada.",
            "Feito, vista recentrada."),
        "Walk around the edge of the free space." to arrayOf(
            "Обойдите край свободного места.",
            "Caminhe pela borda do espaço livre.",
            "Percorra o limite do espaço livre."),
        "Leaving VR." to arrayOf("Выхожу из VR.", "Saindo do VR.", "A sair de VR."),
        "Opening." to arrayOf("Открываю.", "Abrindo.", "A abrir."),
        // Strings that used to live only in the code while Russian was the source language.
        "Avatar" to arrayOf("Аватар", "Avatar", "Avatar"),
        "Avatar saved" to arrayOf("Аватар сохранён", "Avatar salvo", "Avatar guardado"),
        "Couldn't download the model" to arrayOf("Не удалось скачать модель", "Não foi possível baixar o modelo", "Não foi possível transferir o modelo"),
        "Couldn't read the file" to arrayOf("Не удалось прочитать файл", "Não foi possível ler o arquivo", "Não foi possível ler o ficheiro"),
        "Current" to arrayOf("Сейчас", "Atual", "Atual"),
        "Standard" to arrayOf("Стандартный", "Padrão", "Padrão"),
        "Standard avatar" to arrayOf("Стандартный аватар", "Avatar padrão", "Avatar padrão"),
        "Create in Avaturn" to arrayOf("Создать в Avaturn", "Criar no Avaturn", "Criar no Avaturn"),
        "Load a .glb / .vrm file" to arrayOf("Загрузить файл .glb / .vrm", "Carregar arquivo .glb / .vrm", "Carregar ficheiro .glb / .vrm"),
        "Back to standard" to arrayOf("Вернуть стандартный", "Voltar ao padrão", "Voltar ao padrão"),
        "Back" to arrayOf("Назад", "Voltar", "Voltar"),
        "Computer" to arrayOf("Компьютер", "Computador", "Computador"),
        "Video" to arrayOf("Видео", "Vídeo", "Vídeo"),
        "PhoneXR Browser" to arrayOf("Браузер PhoneXR", "Navegador PhoneXR", "Navegador PhoneXR"),
        "Car mode" to arrayOf("Режим машины", "Modo carro", "Modo carro"),
        "Car mode: 3DoF, windows follow your gaze" to arrayOf(
            "Режим машины: 3DoF, окна следуют за взглядом",
            "Modo carro: 3DoF, as janelas seguem o seu olhar",
            "Modo carro: 3DoF, as janelas seguem o seu olhar"),
        "Connected" to arrayOf("Подключено", "Conectado", "Ligado"),
        "Not connected" to arrayOf("Не подключено", "Não conectado", "Não ligado"),
        "On" to arrayOf("Включён", "Ligado", "Ligado"),
        "Off" to arrayOf("Выключен", "Desligado", "Desligado"),
        "Stream from the computer" to arrayOf("Стрим с компьютера", "Transmissão do computador", "Transmissão do computador"),
        // Account, friends and calls
        "Sign in to your account" to arrayOf("Войдите в аккаунт", "Entre na sua conta", "Inicie sessão na sua conta"),
        "Your username" to arrayOf("Ваш юзернейм", "Seu nome de usuário", "O seu nome de utilizador"),
        "Username: 3–20 characters, a–z, 0–9, _ and ." to arrayOf(
            "Юзернейм: 3–20 символов, a–z, 0–9, _ и .",
            "Nome de usuário: 3–20 caracteres, a–z, 0–9, _ e .",
            "Nome de utilizador: 3–20 caracteres, a–z, 0–9, _ e ."),
        "3–20 characters: a–z, 0–9, _ and . Friends will find you by it." to arrayOf(
            "3–20 символов: a–z, 0–9, _ и . По нему вас найдут друзья.",
            "3–20 caracteres: a–z, 0–9, _ e . É assim que os amigos vão encontrar você.",
            "3–20 caracteres: a–z, 0–9, _ e . É assim que os amigos o encontram."),
        "That username is already taken" to arrayOf("Этот юзернейм уже занят", "Esse nome de usuário já está em uso", "Esse nome de utilizador já está em uso"),
        "Add friends by username and call them as your Persona in VR" to arrayOf(
            "Добавляйте друзей по юзернейму и звоните им персоной в VR",
            "Adicione amigos pelo nome de usuário e ligue para eles como sua Persona em VR",
            "Adicione amigos pelo nome de utilizador e ligue-lhes como a sua Persona em VR"),
        "Friends and calls need a PhoneXR account." to arrayOf(
            "Друзья и звонки работают с аккаунтом PhoneXR.",
            "Amigos e chamadas funcionam com uma conta PhoneXR.",
            "Amigos e chamadas funcionam com uma conta PhoneXR."),
        "You can call from the Calls app in the headset while a friend is online." to arrayOf(
            "Позвонить можно из приложения «Звонки» в шлеме, когда друг в сети.",
            "Você pode ligar pelo app Chamadas no headset enquanto o amigo estiver online.",
            "Pode ligar pela app Chamadas no headset enquanto o amigo estiver online."),
        // Cardboard QR, onboarding and settings search
        "The camera is needed to scan the QR" to arrayOf("Камера нужна для сканирования QR", "A câmera é necessária para ler o QR", "A câmara é necessária para ler o QR"),
        "Point the camera at the QR code of the Cardboard profile" to arrayOf(
            "Наведите камеру на QR‑код профиля Cardboard",
            "Aponte a câmera para o QR code do perfil Cardboard",
            "Aponte a câmara para o código QR do perfil Cardboard"),
        "Opening the headset profile…" to arrayOf("Открываю профиль шлема…", "Abrindo o perfil do headset…", "A abrir o perfil do headset…"),
        "A QR was found, but it is not a Cardboard profile" to arrayOf(
            "QR найден, но это не профиль Cardboard",
            "Um QR foi encontrado, mas não é um perfil Cardboard",
            "Foi encontrado um QR, mas não é um perfil Cardboard"),
        "No camera needed: aim the dot in the center at a button and tap the phone screen." to arrayOf(
            "Камера не нужна: наведите точку в центре на кнопку и коснитесь экрана телефона.",
            "Sem câmera: mire o ponto central em um botão e toque na tela do celular.",
            "Sem câmara: aponte o ponto central a um botão e toque no ecrã do telemóvel."),
        "Look at the floor, the walls and the table — PhoneXR covers them with a grid and remembers where the table is: the keyboard will lie on it." to arrayOf(
            "Осмотрите пол, стены и стол — PhoneXR покроет их сеткой и запомнит, где стоит стол: на нём будет клавиатура.",
            "Olhe para o chão, as paredes e a mesa — o PhoneXR os cobre com uma grade e lembra onde fica a mesa: o teclado ficará nela.",
            "Olhe para o chão, as paredes e a mesa — o PhoneXR cobre-os com uma grelha e lembra-se de onde está a mesa: o teclado ficará nela."),
        "Device, version, account" to arrayOf("Устройство, версия, аккаунт", "Dispositivo, versão, conta", "Dispositivo, versão, conta"),
        "Network, controllers, keyboard" to arrayOf("Сеть, контроллеры, клавиатура", "Rede, controles, teclado", "Rede, controlos, teclado"),
        "Hands and touch" to arrayOf("Руки и касания", "Mãos e toque", "Mãos e toque"),
        "Hand tracking, pinch" to arrayOf("Трекинг рук, щипок", "Rastreamento das mãos, pinça", "Rastreio das mãos, pinça"),
        "PhoneXR version" to arrayOf("Версия PhoneXR", "Versão do PhoneXR", "Versão do PhoneXR"),
        "Avaturn or VRoid Hub" to arrayOf("Avaturn или VRoid Hub", "Avaturn ou VRoid Hub", "Avaturn ou VRoid Hub"),
        "Grid, table, 6DoF" to arrayOf("Сетка, стол, 6DoF", "Grade, mesa, 6DoF", "Grelha, mesa, 6DoF"),
        "Search in settings" to arrayOf("Поиск в настройках", "Pesquisar nos ajustes", "Pesquisar nas definições"),
        "Nothing found" to arrayOf("Ничего не найдено", "Nada encontrado", "Nada encontrado"),
        "Start a new scan" to arrayOf("Начать новое сканирование", "Iniciar um novo escaneamento", "Iniciar uma nova digitalização"),
        // Minecraft mods
        "Install Minecraft first" to arrayOf("Сначала установите Minecraft", "Instale o Minecraft primeiro", "Instale o Minecraft primeiro"),
        "Minecraft didn't open the mod" to arrayOf("Minecraft не открыл мод", "O Minecraft não abriu o mod", "O Minecraft não abriu o mod"),
        "This is not a Minecraft mod: a .mcaddon, .mcpack, .mcworld or .mctemplate is needed" to arrayOf(
            "Это не мод Minecraft: нужен .mcaddon, .mcpack, .mcworld или .mctemplate",
            "Isto não é um mod do Minecraft: é preciso um .mcaddon, .mcpack, .mcworld ou .mctemplate",
            "Isto não é um mod do Minecraft: é preciso um .mcaddon, .mcpack, .mcworld ou .mctemplate"),
        "The mod is open in Minecraft: confirm the import" to arrayOf(
            "Мод открыт в Minecraft: подтвердите импорт",
            "O mod está aberto no Minecraft: confirme a importação",
            "O mod está aberto no Minecraft: confirme a importação"),
        "Add-ons, resource packs and worlds for Minecraft Bedrock (.mcaddon, .mcpack, .mcworld). " to arrayOf(
            "Дополнения, наборы ресурсов и миры для Minecraft Bedrock (.mcaddon, .mcpack, .mcworld). ",
            "Complementos, pacotes de recursos e mundos para Minecraft Bedrock (.mcaddon, .mcpack, .mcworld). ",
            "Extras, pacotes de recursos e mundos para Minecraft Bedrock (.mcaddon, .mcpack, .mcworld). "),
    )
}

/** The chosen language's text for an English UI string. */
fun tr(en: String) = L10n.tr(en)
