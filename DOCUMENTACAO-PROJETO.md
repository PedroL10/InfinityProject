# Documentação do Projeto — sb-ecom (API de E-commerce)

Documento de arquitetura e referência técnica do projeto, mantido como material de apoio para explicar o projeto em entrevistas. Diferente do `notas-de-estudo.md` (que registra perguntas e respostas pontuais sobre conceitos), este arquivo descreve o projeto como um todo: visão geral, arquitetura, decisões de design e pontos de atenção.

> Última atualização: 2026-10-02 (módulo de autenticação — Spring Security + JWT)

---

## 1. Visão geral (pitch de entrevista)

É uma **API REST de e-commerce** construída em **Spring Boot**, com CRUD de Categorias e Produtos (com upload de imagem, paginação e busca) e um módulo completo de **autenticação stateless com JWT** (cadastro, login, logout, papéis de usuário). O projeto segue arquitetura em camadas (Controller → Service → Repository), usa **JPA/Hibernate** para persistência, **Bean Validation** para validação de entrada, **DTOs** para não expor a entidade de banco diretamente na API, **Spring Security** para autenticação/autorização, e um **tratamento de exceções centralizado** via `@RestControllerAdvice`.

Frase curta para entrevista: *"É uma API REST em Spring Boot para um sistema de e-commerce, com CRUD de Categorias e Produtos e um módulo de autenticação stateless com JWT entregue via cookie HTTP. Apliquei arquitetura em camadas, injeção de dependência via interfaces, DTOs com ModelMapper (simétrico — entrada e saída) para desacoplar a API do modelo de persistência, validação de entrada com Bean Validation, paginação e ordenação via Spring Data (`Pageable`/`Sort`), tratamento de exceções centralizado com `@RestControllerAdvice` com um envelope de resposta padronizado (`APIResponse`), e Spring Security com um filtro JWT customizado pra autenticação sem estado no servidor."*

## 2. Stack tecnológica

| Tecnologia | Papel no projeto |
|---|---|
| **Java 21** | Linguagem |
| **Spring Boot 4.1.1** | Framework principal (autoconfiguração, servidor embutido) |
| **Spring Web (MVC)** | Controllers REST (`@RestController`) |
| **Spring Data JPA** | Camada de persistência (`JpaRepository`) |
| **Hibernate** | Implementação de JPA (ORM) |
| **H2 Database** | Banco de dados em memória (usado em desenvolvimento) |
| **Bean Validation** (`spring-boot-starter-validation`) | Validação declarativa (`@NotBlank`, `@Size`) |
| **ModelMapper** | Conversão automática Entity ↔ DTO |
| **Lombok** | Geração automática de getters/setters/construtores (`@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| **Spring Security** | Autenticação/autorização, filtro de requisições, `PasswordEncoder` (BCrypt) |
| **JJWT** (`io.jsonwebtoken`) | Geração/validação de tokens JWT |
| **DevTools** | Restart automático da aplicação durante desenvolvimento |

## 3. Arquitetura em camadas

```
Cliente HTTP
     │
     ▼
┌─────────────────────┐
│    Controller        │  @RestController — recebe HTTP, valida entrada (@Valid),
│  CategoryController   │  delega pro service, devolve ResponseEntity
└─────────┬────────────┘
          │  depende da INTERFACE
          ▼
┌─────────────────────┐
│      Service          │  interface CategoryService (contrato)
│  CategoryService /     │  + CategoryServiceImpl (regra de negócio,
│  CategoryServiceImpl   │    validações de domínio, conversão Entity↔DTO)
└─────────┬────────────┘
          │
          ▼
┌─────────────────────┐
│     Repository         │  interface CategoryRepository extends JpaRepository
│  CategoryRepository     │  (Spring Data gera a implementação em runtime)
└─────────┬────────────┘
          │
          ▼
┌─────────────────────┐
│        Model            │  @Entity Category — mapeada para a tabela "categories"
│       Category           │
└─────────┬────────────┘
          │
          ▼
     Banco H2 (em memória)
```

Transversal a todas as camadas:
- **`payload/`** — DTOs (`CategoryDTO`, `CategoryResponse`, `APIResponse`) que trafegam entre Controller e cliente, mantendo a entidade `Category` isolada dentro do backend. `Category` agora nunca cruza a fronteira do Controller (nem entrada, nem saída) — só `CategoryDTO`.
- **`exceptions/`** — exceções de domínio (`ResourceNotFoundException`, `APIException`) + handler global (`MyGlobalExceptionHandler`) que intercepta erros de qualquer camada e os converte em respostas HTTP (usando `APIResponse` como corpo padronizado).
- **`config/`** — configuração de beans que não vêm prontos do Spring Boot (`AppConfig` define o bean `ModelMapper`) e constantes de configuração compartilhadas (`AppConstants` — valores padrão de paginação/ordenação).

## 4. Estrutura de pacotes

```
com.ecommerce.project
├── ProjectApplication.java     # classe main (@SpringBootApplication)
├── config/
│   ├── AppConfig.java            # define o bean ModelMapper
│   └── AppConstants.java         # valores padrão de paginação/ordenação
├── controller/
│   ├── CategoryController.java  # endpoints de Categoria
│   ├── ProductController.java    # endpoints de Produto
│   └── AuthController.java       # endpoints de autenticação (/api/auth/**)
├── service/
│   ├── CategoryService.java / CategoryServiceImpl.java
│   ├── ProductService.java / ProductServiceImpl.java
│   └── FileService.java / FileServiceImpl.java   # upload de imagem de produto
├── repositories/
│   ├── CategoryRepository.java
│   ├── ProductRepository.java
│   ├── UserRepository.java
│   └── RoleRepository.java
├── model/
│   ├── Category.java   # @OneToMany Product
│   ├── Product.java    # @ManyToOne Category, @ManyToOne User (vendedor)
│   ├── User.java        # @ManyToMany Role, @ManyToMany Address, @OneToMany Product
│   ├── Role.java / AppRole.java (enum)
│   └── Address.java
├── payload/
│   ├── CategoryDTO.java / CategoryResponse.java
│   ├── ProductDTO.java / ProductResponse.java
│   └── APIResponse.java           # DTO envelope {message, status} para respostas de erro
├── exceptions/
│   ├── ResourceNotFoundException.java
│   ├── APIException.java
│   └── MyGlobalExceptionHandler.java
└── security/
    ├── WebSecurityConfig.java          # beans de segurança, filterChain, dados de teste (CommandLineRunner)
    ├── jwt/
    │   ├── JwtUtils.java                # gera/valida token, empacota em cookie
    │   ├── AuthTokenFilter.java         # roda em toda requisição, popula o SecurityContext
    │   └── AuthEntryPointJwt.java       # devolve 401 JSON quando falta autenticação
    ├── request/
    │   ├── LoginRequest.java
    │   └── SignupRequest.java
    ├── response/
    │   ├── MessageResponse.java
    │   └── UserInfoResponse.java
    └── services/
        ├── UserDetailsImpl.java         # adapta User -> UserDetails do Spring Security
        └── UserDetailsServiceImpl.java  # busca o usuário pro Spring Security
```

## 5. Fluxo completo de uma requisição — exemplo: `GET /api/public/categories?pageNumber=0&pageSize=5&sortBy=categoryName&sortOrder=desc`

1. Cliente faz o `GET` com os 4 parâmetros de query string (ou omite algum — os defaults de `AppConstants` entram em ação via `@RequestParam(defaultValue = ...)`).
2. `CategoryController.getAllCategories(...)` chama `categoryService.getAllCategories(pageNumber, pageSize, sortBy, sortOrder)`.
3. `CategoryServiceImpl.getAllCategories(...)`:
   - monta um `Sort` (`Sort.by(sortBy).ascending()/.descending()`) e um `Pageable` (`PageRequest.of(pageNumber, pageSize, sort)`);
   - busca via `categoryRepository.findAll(pageDetails)`, que devolve um `Page<Category>` (itens da página + metadados: total de elementos, total de páginas, se é a última);
   - se a página não tiver itens, lança `APIException("No category created till now.")`;
   - converte cada `Category` (entidade) em `CategoryDTO` usando `modelMapper.map(category, CategoryDTO.class)` — evita expor a entidade JPA diretamente na API;
   - empacota a lista de DTOs + os metadados de paginação dentro de um `CategoryResponse`.
4. O Controller devolve `ResponseEntity<CategoryResponse>` com status 200.
5. Se, em vez disso, a página estivesse vazia, a `APIException` lançada no passo 3 subiria sem tratamento local, seria interceptada por `MyGlobalExceptionHandler.myAPIException`, que devolveria um `APIResponse` (`{message, status: false}`) com 400 Bad Request.

Fluxo análogo para `POST`/`PUT`/`DELETE`, todos recebendo/devolvendo `CategoryDTO` (nunca a entidade `Category` diretamente) — o service converte DTO→Entity antes de persistir e Entity→DTO antes de responder, via `ModelMapper`, nos dois sentidos.

## 6. Padrões de projeto e decisões aplicadas

| Padrão / decisão | Onde aparece | Por quê |
|---|---|---|
| **Interface + Implementação** (`Service`/`ServiceImpl`) | `CategoryService` / `CategoryServiceImpl` | Desacopla o Controller da implementação concreta; facilita testes (mock da interface); convenção Spring. |
| **DTO (Data Transfer Object)** | `CategoryDTO`, `CategoryResponse` | Evita expor a entidade JPA (`Category`) diretamente na API — desacopla o contrato público da API do modelo de persistência interno. Mudanças no banco não quebram automaticamente o contrato da API, e vice-versa. |
| **Object Mapper automático** | `ModelMapper` em `CategoryServiceImpl` | Evita escrever manualmente `dto.setCategoryId(entity.getCategoryId())` para cada campo — mapeia por convenção (nomes de campo iguais). |
| **Repository Pattern** | `CategoryRepository extends JpaRepository` | Spring Data gera a implementação do CRUD (e de métodos derivados como `findByCategoryName`) em tempo de execução, sem escrever SQL manualmente. |
| **Exception Handling centralizado** | `MyGlobalExceptionHandler` (`@RestControllerAdvice`) | Um único lugar decide qual status HTTP corresponde a cada tipo de erro, evitando `try/catch` repetido em cada endpoint. |
| **Validação declarativa (Bean Validation)** | `@NotBlank`, `@Size` em `Category` + `@Valid` no Controller | Validação de entrada roda automaticamente antes do método do controller ser chamado; erros viram `MethodArgumentNotValidException`, tratada globalmente. |
| **Redução de boilerplate com Lombok** | `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` em `Category`, `CategoryDTO`, `CategoryResponse`, `APIResponse` | Gera getters, setters, `equals`/`hashCode`, `toString` e construtores automaticamente em tempo de compilação. |
| **Paginação e ordenação via Spring Data** | `Pageable`/`PageRequest`/`Sort`/`Page<Category>` em `CategoryServiceImpl.getAllCategories` | Evita carregar a tabela inteira em memória; Spring Data gera a query com `LIMIT`/`OFFSET`/`ORDER BY` a partir de objetos, sem SQL manual. |
| **Query params com valor padrão centralizado** | `@RequestParam(defaultValue = AppConstants...)` no Controller + `AppConstants` | Evita strings mágicas espalhadas; um único lugar define os padrões de paginação/ordenação da API. |
| **Envelope de resposta padronizado para erros** | `APIResponse` usado em `MyGlobalExceptionHandler` | Erros voltam como objeto estruturado (`{message, status}`), consistente com o resto da API, em vez de texto cru. |

## 7. Endpoints da API

### Categoria
| Método | Endpoint | Query params | Request body | Resposta de sucesso | Camada que valida/lança erro |
|---|---|---|---|---|---|
| GET | `/api/public/categories` | `pageNumber`, `pageSize`, `sortBy`, `sortOrder` | — | 200 + `CategoryResponse` | `APIException` (400) se a página não tiver categorias |
| POST | `/api/public/categories` | — | `CategoryDTO` | 201 + `CategoryDTO` criado | `@Valid` (400); `APIException` (400) se nome duplicado |
| PUT | `/api/public/categories/{categoryId}` | — | `CategoryDTO` | 200 + `CategoryDTO` atualizado | `ResourceNotFoundException` (404) |
| DELETE | `/api/admin/categories/{categoryId}` | — | — | 200 + `CategoryDTO` removido | `ResourceNotFoundException` (404) |

### Produto
| Método | Endpoint | Query params | Request body | Resposta de sucesso | Camada que valida/lança erro |
|---|---|---|---|---|---|
| POST | `/api/admin/categories/{categoryId}/product` | — | `ProductDTO` | 201 + `ProductDTO` criado | `@Valid` (400); `ResourceNotFoundException` (404) se categoria não existir; `APIException` (400) se produto já existe |
| GET | `/api/public/products` | `pageNumber`, `pageSize`, `sortBy`, `sortOrder` | — | 200 + `ProductResponse` | — |
| GET | `/api/public/categories/{categoryId}/products` | idem | — | 200 + `ProductResponse` | `ResourceNotFoundException` (404); `APIException` (400) se categoria sem produtos |
| GET | `/api/public/products/keyword/{keyword}` | idem | — | 200 + `ProductResponse` | `APIException` (400) se nada encontrado |
| PUT | `/api/admin/products/{productId}` | — | `ProductDTO` | 200 + `ProductDTO` atualizado | `ResourceNotFoundException` (404) |
| DELETE | `/api/admin/products/{productId}` | — | — | 200 + `ProductDTO` removido | `ResourceNotFoundException` (404) |
| PUT | `/api/products/{productId}/image` | — | `multipart/form-data` (`image`) | 200 + `ProductDTO` com imagem atualizada | `ResourceNotFoundException` (404) |

### Autenticação
| Método | Endpoint | Request body | Resposta de sucesso | Observação |
|---|---|---|---|---|
| POST | `/api/auth/signup` | `SignupRequest` | 200 + `MessageResponse` | `APIException`/400 se username ou email já existem |
| POST | `/api/auth/signin` | `LoginRequest` | 200 + `UserInfoResponse` + cookie `Set-Cookie` com o JWT | credenciais inválidas → **404** (deveria ser 401 — ver seção 9) |
| GET | `/api/auth/username` | — | 200 + username (texto puro) | requer cookie JWT válido |
| GET | `/api/auth/user` | — | 200 + `UserInfoResponse` | requer cookie JWT válido |
| POST | `/api/auth/signout` | — | 200 + `MessageResponse`, cookie JWT limpo | — |

Erros (`APIException`/`ResourceNotFoundException`) sempre voltam como `APIResponse` (`{message, status: false}`); falhas de `@Valid` voltam como `Map<String,String>` (`{campo: mensagem}`).

> **Importante**: no `WebSecurityConfig` atual, as regras `permitAll()` para `/api/admin/**` e `/api/public/**` estão **comentadas** — ou seja, **todos** os endpoints de Categoria e Produto acima exigem um cookie JWT válido (login prévio), exceto `/api/auth/**`, `/h2-console/**`, `/swagger-ui/**`, `/api/test/**` e `/images/**`. Veja o guia de testes (seção 11) para o passo a passo de login antes de testar Categoria/Produto.

## 8. Conceitos para citar numa entrevista técnica

- **Inversão de Controle / Injeção de Dependência**: `@Autowired` + programar contra interfaces (`CategoryService`, `ModelMapper` como bean gerenciado).
- **ORM e geração de schema**: `@Entity`, `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)` — Hibernate cria a tabela automaticamente a partir da entidade (`ddl-auto` implícito do H2 em memória).
- **Query Methods do Spring Data**: `findByCategoryName(String categoryName)` — o Spring Data gera a implementação SQL a partir do nome do método, sem escrever `@Query`.
- **Separação Entity vs DTO**: por que a API não deveria devolver a entidade JPA diretamente (evita vazar detalhes de persistência, evita problemas de serialização com proxies do Hibernate, dá controle total sobre o que trafega na API).
- **Tratamento de exceções global com `@RestControllerAdvice`/`@ExceptionHandler`**: como o Spring MVC intercepta exceções não capturadas nos controllers.
- **Bean Validation**: anotações declarativas de validação e como o Spring dispara `MethodArgumentNotValidException`.

## 9. Pontos de atenção / melhorias conhecidas

Ótimo material para entrevista ("o que você faria diferente / o que sabe que está incompleto"). Organizado por módulo e por severidade (🔴 alta, 🟡 média, 🟢 baixa/estilo).

### Ainda em aberto — Autenticação (módulo novo)
1. 🔴 **`/api/admin/**` e `/api/public/**` comentados no `WebSecurityConfig`** — hoje **tudo** que não é `/api/auth/**`, `/h2-console/**`, `/swagger-ui/**`, `/api/test/**` ou `/images/**` exige login. Decidir conscientemente: reativar `permitAll()` nos endpoints que devem ser públicos (leitura de categorias/produtos, por exemplo), ou manter tudo protegido e ajustar o fluxo esperado do cliente.
2. 🔴 **Credenciais inválidas no login devolvem 404** (`AuthController.authenticateUser`) — deveria ser **401 Unauthorized**; 404 sugere "recurso não existe", não "senha errada".
3. 🟡 **Cookie JWT gerado com `httpOnly(false)`** (`JwtUtils.generateJwtCookie`) — o token fica acessível via JavaScript no navegador, vulnerável a roubo via XSS. O padrão recomendado é `httpOnly(true)`.
4. 🟡 **Race condition no cadastro** (`AuthController.registerUser`) — `existsByUserName`/`existsByEmail` (checagem) e `save` (gravação) são operações separadas sem trava; a tabela `users` já tem `@UniqueConstraint`, mas não há captura de `DataIntegrityViolationException`, então uma corrida concorrente gera um 500 cru em vez de mensagem amigável (mesmo padrão já corrigido em Categoria — ver item 17).
5. 🟡 **`UserDetailsImpl` sobrescreve `equals()` sem sobrescrever `hashCode()`** — viola o contrato Java; risco de bug sutil se o objeto for usado como chave de `HashMap`/`HashSet`.
6. 🟢 **Papéis de cadastro como strings soltas** (`SignupRequest.role: Set<String>`, comparadas num `switch` em `AuthController`) — um valor não reconhecido cai silenciosamente no `default` (`ROLE_USER`), sem avisar o cliente que o papel pedido era inválido.
7. 🟢 **`spring.app.jwtSecret` hardcoded em `application.properties` versionado** — aceitável em projeto de estudo; em produção iria para variável de ambiente/secret manager.

### Ainda em aberto — Produto
8. 🟡 **`Product.productName` sem constraint `unique` no banco** (diferente de `Category.categoryName`) — a checagem de duplicidade em `addProduct` roda em memória, iterando `category.getProducts()`, sem proteção real contra concorrência.
9. 🟡 **`updateProduct` não valida nome duplicado** — permite atualizar um produto para um nome já usado por outro produto da mesma categoria.
10. 🟢 **`Product` usa `GenerationType.AUTO`, `Category`/`User` usam `GenerationType.IDENTITY`** — inconsistência de estratégia de geração de ID entre entidades do mesmo projeto.
11. 🟢 **`Category.products` sem inicializador** (`private List<Product> products;`, sem `= new ArrayList<>()`) — diferente de `User.addresses`/`User.products`. Risco baixo (Hibernate popula a coleção ao carregar via JPA), mas inconsistente e arriscado se a entidade for construída manualmente (ex. em um teste).

### Ainda em aberto — Categoria
12. 🟢 **`sortBy` não é validado** contra os campos reais da entidade — nome de campo inexistente só falha em runtime.
13. 🟢 **Sem handler genérico para exceções inesperadas** (`@ExceptionHandler(Exception.class)`) — qualquer exceção fora de `MethodArgumentNotValidException`/`ResourceNotFoundException`/`APIException` cai no tratamento padrão do Spring, fora do formato `APIResponse`.
14. 🟢 **`Category.categoryName` sem `@Size(max=...)`** — só tem `min = 5`.
15. 🟢 **`CategoryResponse.totalpages` foge do padrão camelCase** (deveria ser `totalPages`).
16. 🟢 **`APIResponse.message` é campo `public`**, inconsistente com `status` (`private` + getter/setter).

### Transversal (todos os módulos)
17. 🟡 **Nenhum teste automatizado em todo o projeto** — nem unitário nem de integração, em Categoria, Produto ou Autenticação.
18. 🟢 **Vários `@Autowired` em campo em vez de injeção via construtor** (sinalizado pelo próprio Spring Tools no VS Code) — construtor facilita testes e permite campos `final`.

### Já corrigidos (histórico)
19. ~~`updateCategory` não retorna os dados atualizados~~ — corrigido: devolve `CategoryDTO` atualizado.
20. ~~Sem paginação real~~ — corrigido com `Pageable`/`Sort`/`Page<Category>`.
21. ~~`ModelMapper` sem bean explícito~~ (2026-09-21) — corrigido com `config/AppConfig.java`.
22. ~~Sem DTO na entrada dos endpoints POST/PUT de Categoria~~ — corrigido.
23. ~~Validação de entrada quebrada silenciosamente em `CategoryDTO`~~ (2026-09-21) — corrigido movendo `@NotBlank`/`@Size` da entidade pro DTO.
24. ~~`categoryId` vazando no POST de criação~~ (2026-09-21) — corrigido com `category.setCategoryId(null)`.
25. ~~Race condition no nome duplicado de Categoria~~ (2026-09-21) — corrigido com `@Column(unique = true)` + captura de `DataIntegrityViolationException`.
26. ~~`application.properties` com `format_sql` recebendo valor de `ddl-auto`~~ (2026-09-21) — corrigido.
27. ~~`ProductDTO` sem validação~~ (2026-09-22) — mesma falha do item 23, agora em Produto; corrigido espelhando `@NotBlank`/`@Size` de `Product`.
28. ~~`productId` vazando no POST de criação de Produto~~ (2026-09-22) — mesma falha do item 24; corrigido com `product.setProductId(null)`.
29. ~~`getProductsByKeyword` devolvia `HttpStatus.FOUND` (302)~~ (2026-09-22) — corrigido para `HttpStatus.OK`.
30. ~~`ProductDTO.specialPrice` primitivo causava crash de desserialização~~ (2026-09-22) — Jackson não conseguia mapear `null` num `double`; corrigido trocando para `Double` (wrapper).
31. ~~`updateProduct` não recalculava `specialPrice`~~ (2026-09-22) — copiava o valor do DTO em vez de recalcular a partir de `price`/`discount`; corrigido para recalcular, igual `addProduct`.
32. ~~Pacotes `security.jwt` declarados errados~~ (2026-10-02) — arquivos moveram fisicamente para `security/jwt/`, pacote corrigido.
33. ~~Import `com.fasterxml.jackson.databind.ObjectMapper` incompatível~~ (2026-10-02) — projeto usa Jackson 3 (Spring Boot 4.1); corrigido para `tools.jackson.databind.ObjectMapper`.
34. ~~`DaoAuthenticationProvider()` sem argumentos e `setUserDetailsService(...)` removidos no Spring Security 7~~ (2026-10-02) — corrigido usando `new DaoAuthenticationProvider(userDetailsService)`.

## 10. Perguntas comuns de entrevista sobre este projeto (e como responder)

**"Por que separar Service de ServiceImpl?"**
→ Ver seção 1 de `notas-de-estudo.md`. Resumo: desacoplamento do contrato, testabilidade (mock de interface), convenção Spring.

**"Por que usar DTO em vez de devolver a entidade direto?"**
→ A entidade `Category` é um detalhe de persistência (tem anotações JPA, é gerenciada pelo Hibernate). O DTO (`CategoryDTO`) é o contrato público da API — podem evoluir de forma independente. Também evita expor campos internos por engano e problemas de serialização de proxies do Hibernate (lazy loading).

**"Como funciona o tratamento de erros?"**
→ Ver seção 4 de `notas-de-estudo.md`. Resumo: exceções de domínio (`APIException`, `ResourceNotFoundException`) lançadas nos services, capturadas globalmente por `MyGlobalExceptionHandler` (`@RestControllerAdvice`), que converte cada tipo em um status HTTP apropriado.

**"Como você geraria o schema do banco?"**
→ Hibernate gera automaticamente a partir das entidades (`@Entity`, `@Id`, `@GeneratedValue`), configurado via `spring.jpa.hibernate.ddl-auto` (implícito/padrão do Spring Boot com H2 em memória: `create-drop`).

**"Como você implementou paginação?"**
→ Ver seção 7 de `notas-de-estudo.md`. Resumo: `@RequestParam` no Controller lê `pageNumber`/`pageSize`/`sortBy`/`sortOrder` da query string (com defaults centralizados em `AppConstants`); o service monta um `Pageable` (`PageRequest`) com `Sort` embutido e chama `JpaRepository.findAll(Pageable)`, que devolve um `Page<Category>` com os itens da página e metadados de paginação, usados para popular `CategoryResponse`.

**"Como funciona a autenticação?"**
→ Ver seção 10 de `notas-de-estudo.md`. Resumo: JWT stateless entregue via cookie HTTP; `AuthTokenFilter` roda em toda requisição, extrai e valida o token, e repopula o `SecurityContext` sem depender de sessão no servidor.

## 11. Guia de testes no Postman

Reinicie a aplicação antes de começar (H2 em memória — dados são recriados do zero, inclusive os usuários de teste do `CommandLineRunner`). Habilite "Automatically follow redirects" e deixe o gerenciador de cookies do Postman ativo (padrão) — ele guarda o cookie JWT automaticamente entre requisições, como um navegador faria.

> Como as regras `permitAll()` de `/api/admin/**` e `/api/public/**` estão comentadas (ver seção 9, item 1), **é preciso logar antes de testar Categoria e Produto** — sem isso, qualquer chamada devolve 401 do `AuthEntryPointJwt`.

### 11.1 Autenticação

**Usuários já existentes** (criados pelo `CommandLineRunner` na subida — não precisa cadastrar pra testar):
| username | password | papéis |
|---|---|---|
| `user1` | `password1` | ROLE_USER |
| `seller1` | `password2` | ROLE_SELLER |
| `admin` | `adminPass` | ROLE_USER, ROLE_SELLER, ROLE_ADMIN |

**1. Cadastro de um novo usuário**
```
POST /api/auth/signup
```
```json
{
    "username": "pedro",
    "email": "pedro@example.com",
    "password": "minhaSenha123",
    "role": ["seller"]
}
```
✅ Esperado: 200, `{"message": "User registered successfully!"}`. `role` é opcional — omitindo, o usuário vira `ROLE_USER`. Valores aceitos: `"admin"`, `"seller"`, qualquer outro (ou ausência) vira `"user"`.

Repetir o mesmo body → 400, `{"message": "Error: Username is already taken!"}`.

**2. Login**
```
POST /api/auth/signin
```
```json
{
    "username": "admin",
    "password": "adminPass"
}
```
✅ Esperado: 200, corpo `UserInfoResponse` (`id`, `username`, `roles`, `jwtToken`) **e** um header `Set-Cookie` com o token — o Postman guarda esse cookie automaticamente para as próximas requisições na mesma aba/environment.

Senha errada:
```json
{ "username": "admin", "password": "errada" }
```
✅ Esperado hoje: **404** (item 2 da seção 9 — deveria ser 401).

**3. Dados do usuário logado**
```
GET /api/auth/user
```
(sem body — usa o cookie salvo automaticamente)
✅ Esperado: 200, `UserInfoResponse` sem `jwtToken`.

**4. Username do usuário logado**
```
GET /api/auth/username
```
✅ Esperado: 200, texto puro com o username.

**5. Logout**
```
POST /api/auth/signout
```
✅ Esperado: 200, `{"message": "You've been signed out!"}`, e o cookie JWT é limpo — chamadas seguintes a endpoints protegidos voltam a dar 401.

### 11.2 Categoria (logado como `admin`, por exemplo)

**6. Criar categoria**
```
POST /api/public/categories
```
```json
{ "categoryName": "Electronics" }
```
✅ 201 + `CategoryDTO` criado (anote o `categoryId`).

**7. Listar categorias**
```
GET /api/public/categories?pageNumber=0&pageSize=10&sortBy=categoryName&sortOrder=asc
```
✅ 200 + `CategoryResponse`.

**8. Atualizar categoria**
```
PUT /api/public/categories/{categoryId}
```
```json
{ "categoryName": "Electronics & Gadgets" }
```
✅ 200 + `CategoryDTO` atualizado.

**9. Deletar categoria**
```
DELETE /api/admin/categories/{categoryId}
```
✅ 200 + `CategoryDTO` removido.

### 11.3 Produto (precisa de uma categoria válida — repita o passo 6 antes)

**10. Criar produto**
```
POST /api/admin/categories/{categoryId}/product
```
```json
{
    "productName": "Wireless Mouse",
    "description": "Ergonomic wireless mouse",
    "quantity": 50,
    "price": 120.00,
    "discount": 10
}
```
✅ 201 + `ProductDTO` criado (`specialPrice` calculado automaticamente: `120 - 12 = 108`; `image: "default.png"`).

**11. Listar todos os produtos**
```
GET /api/public/products?pageNumber=0&pageSize=10&sortBy=productId&sortOrder=asc
```
✅ 200 + `ProductResponse`.

**12. Listar produtos de uma categoria**
```
GET /api/public/categories/{categoryId}/products
```
✅ 200 + `ProductResponse`.

**13. Buscar por palavra-chave**
```
GET /api/public/products/keyword/mouse
```
✅ 200 + `ProductResponse` (produtos cujo nome contém "mouse", case-insensitive).

**14. Atualizar produto**
```
PUT /api/admin/products/{productId}
```
```json
{
    "productName": "Wireless Mouse Pro",
    "description": "Ergonomic wireless mouse, upgraded",
    "quantity": 40,
    "price": 150.00,
    "discount": 15
}
```
✅ 200 + `ProductDTO` atualizado (`specialPrice` recalculado: `150 - 22.5 = 127.5`).

**15. Atualizar imagem do produto**
```
PUT /api/products/{productId}/image
```
Body → `form-data`, chave `image` do tipo **File**, selecione uma imagem local.
✅ 200 + `ProductDTO` com `image` trocado pro nome gerado (`uuid.extensão`), salvo em `images/`.

**16. Deletar produto**
```
DELETE /api/admin/products/{productId}
```
✅ 200 + `ProductDTO` removido.

### 11.4 Checklist geral
- [ ] Sem logar, qualquer chamada a Categoria/Produto → 401 (`AuthEntryPointJwt`).
- [ ] Login com usuário/senha certos → 200 + cookie; senha errada → 404 (comportamento atual, ver seção 9).
- [ ] Cadastro com username/email repetido → 400 com mensagem clara.
- [ ] Criar categoria/produto com nome curto/vazio → 400 com erros por campo.
- [ ] Criar produto em categoria inexistente → 404.
- [ ] Atualizar/deletar categoria ou produto com ID inexistente → sempre 404, nunca 500.
- [ ] Logout seguido de uma chamada protegida → 401 de novo.

---

*Documento vivo — atualizar a cada nova funcionalidade, camada ou decisão de design relevante adicionada ao projeto.*
