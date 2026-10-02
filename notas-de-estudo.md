# Notas de Estudo — Spring Boot (projeto Infinite)

Anotações pessoais sobre conceitos estudados neste projeto, para consulta e revisão.

---

## 1. Por que separar `Service` e `ServiceImpl`?

### Contexto
No projeto, `CategoryService` é uma **interface** e `CategoryServiceImpl` é a **classe que a implementa**. O `CategoryController` depende do tipo `CategoryService` (a interface), não de `CategoryServiceImpl` diretamente.

```java
// CategoryService.java — o contrato
public interface CategoryService {
    List<Category> getAllCategories();
    void createCategory(Category category);
    String deleteCategory(Long categoryId);
    Category updateCategory(Category category, Long categoryId);
}

// CategoryServiceImpl.java — a implementação real
@Service
public class CategoryServiceImpl implements CategoryService {
    @Override
    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }
    // ...
}

// CategoryController.java — depende da interface
@Autowired
private CategoryService categoryService;
```

### Explicação
- A **interface** define *o que* o serviço faz (assinaturas dos métodos).
- A **implementação** define *como* ele faz (a lógica de verdade).
- O Controller conhece só o contrato; o Spring, em tempo de execução, injeta automaticamente a implementação concreta (`CategoryServiceImpl`) no campo do tipo `CategoryService`.

### Vantagens
1. **Desacoplamento** — quem usa o serviço não depende de detalhes de implementação; a implementação pode ser trocada sem alterar quem a consome.
2. **Testabilidade** — é fácil criar um *mock* de uma interface (ex.: com Mockito) para testar o Controller isoladamente, sem precisar de banco de dados real.
3. **Convenção Spring** — é o padrão idiomático em arquitetura em camadas (Controller → Service → Repository).
4. **Múltiplas implementações futuras** — permite trocar/adicionar implementações (ex.: uma versão com cache) sem tocar no restante do sistema.

### Isso é obrigatório?
**Não.** O Spring não exige essa separação — funcionaria também com uma única classe `@Service` sem interface. É uma convenção histórica e uma boa prática de design ("programe para interfaces"), especialmente útil quando há testes ou possibilidade real de múltiplas implementações. No projeto atual, com uma única implementação, o ganho principal e imediato é testabilidade e organização em camadas.

### 📌 Resumo
A interface `CategoryService` define o contrato (quais métodos existem) e a classe `CategoryServiceImpl` contém a implementação real desses métodos; o Controller é escrito para depender apenas da interface, e o Spring, em tempo de execução, injeta a implementação concreta automaticamente — isso desacopla quem usa o serviço de como ele funciona por dentro, facilita trocar a implementação no futuro sem alterar quem a consome, e sobretudo facilita testes unitários (é fácil criar um mock de uma interface); no projeto, com uma única implementação, o ganho imediato é mais testabilidade e organização em camadas do que polimorfismo real, e essa separação é uma convenção comum em Spring, não uma exigência do framework.

---

## 2. Por que usar `ResponseEntity`?

### Contexto
No `CategoryController`, os métodos retornam `ResponseEntity<T>` em vez do objeto puro:

```java
@GetMapping("/public/categories")
public ResponseEntity<List<Category>> getAllCategories(){
    List<Category> categories = categoryService.getAllCategories();
    return new ResponseEntity<>(categories, HttpStatus.OK);
}

@DeleteMapping("/admin/categories/{categoryId}")
public ResponseEntity<String> deleteCategory(@PathVariable Long categoryId){
    try {
        String status = categoryService.deleteCategory(categoryId);
        return ResponseEntity.status(HttpStatus.OK).body(status);
    } catch (ResponseStatusException e){
        return new ResponseEntity<>(e.getReason(), e.getStatusCode());
    }
}
```

### Explicação
Uma resposta HTTP tem três partes: **status code** (200, 201, 404...), **headers** e **corpo**. Sem `ResponseEntity`, o Spring sempre devolveria status 200 por padrão, independente do que aconteceu. `ResponseEntity<T>` permite controlar essas três partes explicitamente e **dinamicamente**, a cada requisição.

### Formas de construir
```java
new ResponseEntity<>(corpo, HttpStatus.OK);           // construtor direto
ResponseEntity.status(HttpStatus.OK).body(corpo);      // builder fluente
ResponseEntity.ok(corpo);                               // atalho para 200
ResponseEntity.notFound().build();                      // atalho para 404
ResponseEntity.created(uri).build();                    // atalho para 201 + header Location
```

### Por que não `@ResponseStatus`?
`@ResponseStatus` fixa o status no método (sempre o mesmo, não importa o resultado). `ResponseEntity` permite decidir o status em tempo de execução — essencial quando o mesmo endpoint pode retornar sucesso ou erro (ex.: 200 se encontrou, 404 se não encontrou).

### Observação sobre o `try/catch`
No projeto, exceções `ResponseStatusException` lançadas no `CategoryServiceImpl` são capturadas manualmente no Controller e convertidas em `ResponseEntity`. Isso funciona, mas é redundante: o Spring já sabe tratar `ResponseStatusException` nativamente. Uma evolução comum é usar um `@ExceptionHandler`/`@ControllerAdvice` global para eliminar o `try/catch` repetido em cada método.

### 📌 Resumo
`ResponseEntity` existe porque uma resposta HTTP é mais do que o corpo JSON — inclui também o status code e headers, e sem ela o Spring sempre devolveria 200 por padrão; usando `ResponseEntity<T>` (seja via construtor, `ResponseEntity.status(status).body(corpo)` ou atalhos como `ResponseEntity.ok(corpo)`) o controller decide dinamicamente, a cada requisição, qual corpo e qual status devolver, o que é essencial numa API REST onde o resultado varia (sucesso, não encontrado, erro de validação etc.); no `CategoryController`, isso aparece nos blocos `try/catch` que convertem uma `ResponseStatusException` lançada no service em um `ResponseEntity` com o status correto, embora esse padrão possa futuramente ser simplificado com um `@ExceptionHandler` global em vez de repetir o `try/catch` em cada método.

---

## 3. Bug: POST em `/api/public/categories` lançava `StaleObjectStateException`

### Contexto
Ao dar POST em `/api/public/categories` com `{"categoryName":"Sports & Fitness"}`, a aplicação lançava:
```
org.hibernate.orm.ObjectOptimisticLockingFailureException: Row was already updated or deleted by another transaction for entity [com.ecommerce.project.model.Category with id '1']
```
mesmo sendo a primeira categoria criada no banco (H2 em memória recém-criado).

Código problemático (`CategoryServiceImpl.java`, antes do fix):
```java
private Long nextId = 1L;
// ...
@Override
public void createCategory(Category category) {
    category.setCategoryId(nextId++);   // <-- atribuição manual do ID
    categoryRepository.save(category);
}
```

E a entidade:
```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long categoryId;
```

### Explicação
`GenerationType.IDENTITY` significa que **o banco de dados** gera o ID no momento do `INSERT` — a aplicação não deveria atribuir esse valor manualmente. O código antigo, resquício de uma versão em que as categorias eram guardadas numa lista em memória (havia até um comentário `//private List<Category> categories = new ArrayList<>()`), continuava setando `categoryId` manualmente antes de chamar `save()`.

Quando o Spring Data JPA recebe uma entidade para salvar cujo `@Id` já não é nulo, ele assume que ela **já existe** no banco e, em vez de fazer `INSERT` (via `persist`), tenta um `UPDATE` (via `merge`) — procurando no banco uma linha com aquele ID. Como a linha nunca existiu, o Hibernate interpreta isso como "a linha existia e foi alterada/apagada por outra transação" e lança `StaleObjectStateException`/`ObjectOptimisticLockingFailureException`, mesmo sem haver campo `@Version` na entidade.

### Correção
Remover a atribuição manual do ID e o campo `nextId`, deixando o banco gerar o ID:
```java
@Override
public void createCategory(Category category) {
    categoryRepository.save(category);
}
```

### 📌 Resumo
O erro acontecia porque `CategoryServiceImpl.createCategory` atribuía manualmente um ID (`category.setCategoryId(nextId++)`) antes de salvar, mesmo a entidade `Category` usando `@GeneratedValue(strategy = GenerationType.IDENTITY)` — ou seja, o ID deveria ser gerado pelo próprio banco no INSERT; ao ver um `@Id` já preenchido, o Spring Data JPA/Hibernate assume que a entidade já existe e tenta um `UPDATE` (via `merge`) em vez de um `INSERT` (via `persist`), e como nenhuma linha com aquele ID existia de fato, o Hibernate lança `StaleObjectStateException` interpretando isso como se a linha tivesse sido alterada por outra transação; a correção foi remover a atribuição manual do ID (e o campo `nextId`, resquício de uma implementação antiga em lista), deixando o banco gerar o identificador normalmente.

---

## 4. Tratamento de exceções: `ResourceNotFoundException`, `APIException` e `MyGlobalExceptionHandler`

### Contexto
O projeto centraliza o tratamento de erros em vez de usar `try/catch` em cada endpoint do controller. Três classes trabalham juntas, todas em `com.ecommerce.project.exceptions`:

```java
// Exceção de domínio para "não encontrado"
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceName, String field, Long fieldId) {
        super(String.format("%s not found with %s: %d", resourceName, field, fieldId));
        ...
    }
}

// Exceção de domínio genérica para regras de negócio violadas
public class APIException extends RuntimeException {
    public APIException(String message) { super(message); }
}

// Interceptador global
@RestControllerAdvice
public class MyGlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> myResourceNotFoundException(ResourceNotFoundException e) {
        return new ResponseEntity<>(e.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(APIException.class)
    public ResponseEntity<String> myAPIException(APIException e) {
        return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> myMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        // monta um mapa campo -> mensagem de erro, a partir das violações do @Valid
    }
}
```

**Onde são lançadas** — em `CategoryServiceImpl`:
```java
if (categories.isEmpty())
    throw new APIException("No category created till now.");
...
if (savedCategory != null)
    throw new APIException("Category with the name " + category.getCategoryName() + " already exists !!!");
...
Category category = categoryRepository.findById(categoryId)
        .orElseThrow(() -> new ResourceNotFoundException("Category", "categoryId", categoryId));
```
E o `CategoryController` não captura mais nada — os métodos deixam as exceções subirem livremente.

### Explicação
- `ResourceNotFoundException` e `APIException` são **exceções de domínio puras**: não conhecem HTTP, `HttpStatus` nem `ResponseEntity`, só carregam uma mensagem. `ResourceNotFoundException` é usada especificamente quando uma busca (`findById`, etc.) não encontra o recurso; `APIException` é o "balde genérico" para outras regras de negócio violadas (lista vazia, nome duplicado).
- `MyGlobalExceptionHandler`, anotada com `@RestControllerAdvice`, intercepta exceções lançadas por **qualquer** `@RestController` da aplicação (não só `CategoryController`). Cada método anotado com `@ExceptionHandler(TipoDaExceção.class)` funciona como um "catch" registrado globalmente: quando uma exceção daquele tipo sobe sem ser capturada em nenhum controller, o Spring a intercepta e executa o handler correspondente, convertendo-a no `ResponseEntity` (status + corpo) correto.
- `MethodArgumentNotValidException` é diferente: não é lançada manualmente — é disparada automaticamente pelo Spring quando `@Valid` (usado em `createCategory`/`updateCategory` no controller) detecta violação de validação Bean Validation no corpo da requisição, antes mesmo do método do controller ser executado.

### Fluxo completo (exemplo: DELETE com ID inexistente)
1. `DELETE /api/admin/categories/999` chega no controller.
2. `categoryService.deleteCategory(999)` → `findById(999)` retorna vazio.
3. `.orElseThrow(...)` lança `ResourceNotFoundException`.
4. A exceção sobe sem ser capturada em nenhum lugar do controller/service.
5. O Spring intercepta e encontra `myResourceNotFoundException` no `MyGlobalExceptionHandler`.
6. O handler devolve `ResponseEntity<>(mensagem, HttpStatus.NOT_FOUND)` → cliente recebe 404 com a mensagem.

Sem esse handler, a exceção não tratada resultaria em **500 Internal Server Error** genérico — semanticamente errado para um caso de "não encontrado".

### Por que essa arquitetura em vez de `try/catch` por endpoint?
Centraliza a regra "que status HTTP corresponde a cada tipo de erro" em um único lugar; qualquer novo endpoint/controller já fica automaticamente coberto, sem precisar escrever tratamento de exceção nenhum; e os services ficam desacoplados do mundo HTTP, lançando apenas exceções de domínio.

### 📌 Resumo
O projeto usa três peças que trabalham juntas para tratar erros de forma centralizada: `ResourceNotFoundException` e `APIException` são exceções de domínio (RuntimeException customizadas) lançadas pelos métodos de `CategoryServiceImpl` quando uma regra de negócio é violada — a primeira para "recurso não encontrado" (ex.: `findById` vazio), a segunda para outras regras (lista vazia, nome duplicado) — e nenhuma das duas sabe nada sobre HTTP, apenas carregam uma mensagem; quem converte essas exceções em respostas HTTP reais é `MyGlobalExceptionHandler`, uma classe anotada com `@RestControllerAdvice` que intercepta exceções lançadas por qualquer controller da aplicação, com um método `@ExceptionHandler` por tipo de exceção (404 para `ResourceNotFoundException`, 400 para `APIException`, e um tratamento especial para `MethodArgumentNotValidException`, disparada automaticamente pelo Spring quando `@Valid` falha na validação do corpo da requisição); essa arquitetura elimina a repetição de `try/catch` em cada endpoint do controller e centraliza, num único lugar, a regra de qual status HTTP corresponde a cada tipo de erro.

---

## 5. Por que usar `Map<String, String>` no handler de validação

### Contexto
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<Map<String, String>> myMethodArgumentNotValidException(MethodArgumentNotValidException e) {
    Map<String, String> response = new HashMap<>();
    e.getBindingResult().getAllErrors().forEach(err -> {
        String fieldName = ((FieldError) err).getField();
        String message = err.getDefaultMessage();
        response.put(fieldName, message);
    });
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
}
```
`Category.categoryName` tem `@NotBlank` e `@Size(min = 5, ...)`. Quando `@Valid` falha no controller, esse handler monta um `Map` associando cada nome de campo à sua mensagem de erro.

### Explicação
- `Map<K, V>` guarda pares **chave → valor** com chaves únicas — diferente de `List`/array, que guardam apenas uma sequência posicional. Aqui a relação natural é **campo → mensagem de erro**, com quantidade variável de entradas (depende de quantos campos falharam).
- `Map` é interface, `HashMap` é a implementação concreta usada (`new HashMap<>()`) — mesmo princípio de "programar para a interface" da seção 1 (`CategoryService`/`CategoryServiceImpl`). Poderia trocar por `LinkedHashMap` (preserva ordem de inserção) ou `TreeMap` (ordena por chave) sem mudar o resto do código.
- **Por que não `List<String>`?** Perderia a associação entre campo e mensagem — o cliente não saberia qual campo gerou qual erro, ruim para exibir erro embaixo do input certo num formulário.
- **Por que não uma classe própria (`List<FieldError>` customizada)?** É uma alternativa válida e mais expressiva, mas exige criar/manter uma classe extra só para dois campos simples (campo + mensagem); `Map` é a solução direta para esse caso simples.
- **Serialização automática**: o Jackson (usado pelo Spring Boot) converte `Map<String, String>` diretamente num objeto JSON `{"campo": "mensagem"}`, formato conveniente para o cliente acessar `erro.categoryName` diretamente.
- **Limitação**: como `Map` não permite chaves duplicadas, se dois erros ocorrerem no mesmo campo (ex.: `@NotBlank` e `@Size` falham juntos), `put()` sobrescreve e só a última mensagem sobrevive.

### 📌 Resumo
`Map<String, String>` foi escolhido porque o problema tem exatamente essa forma — associação chave única → valor (nome do campo → mensagem de erro), com quantidade variável de entradas; a variável é declarada como a interface `Map` mas instanciada como `HashMap` (mesmo princípio de programar para a interface já visto entre `CategoryService`/`CategoryServiceImpl`), podendo trocar por `LinkedHashMap` ou `TreeMap` sem alterar o resto do código; alternativas como `List<String>` ou array perderiam a associação entre campo e mensagem, enquanto uma classe própria seria mais expressiva mas exigiria criar uma classe extra só para isso; e o Jackson converte esse `Map` automaticamente num objeto JSON `{"campo": "mensagem"}`, formato conveniente para o cliente consumir — com a ressalva de que, como `Map` não permite chaves duplicadas, se dois erros ocorrerem no mesmo campo, só a última mensagem sobrevive.

---

## 6. O que são DTOs, vantagens e quando (não) usar

### Contexto
```java
// payload/CategoryDTO.java
@Data @NoArgsConstructor @AllArgsConstructor
public class CategoryDTO {
    private Long categoryId;
    private String categoryName;
}

// payload/CategoryResponse.java — DTO "envelope"
@Data @NoArgsConstructor @AllArgsConstructor
public class CategoryResponse {
    private List<CategoryDTO> content;
}
```
Uso em `CategoryServiceImpl.getAllCategories()`:
```java
List<Category> categories = categoryRepository.findAll();               // entidades JPA
List<CategoryDTO> categoryDTOS = categories.stream()
        .map(category -> modelMapper.map(category, CategoryDTO.class))  // Entity -> DTO
        .toList();
CategoryResponse categoryResponse = new CategoryResponse();
categoryResponse.setContent(categoryDTOS);
```

### Explicação
DTO (Data Transfer Object) é um objeto sem lógica de negócio nem anotações de persistência, cuja única função é carregar dados entre camadas/sistemas. Diferente da entidade `Category` (que representa uma linha da tabela `categories`, com `@Entity`/`@Id`/`@GeneratedValue`), o `CategoryDTO` representa o formato que trafega pela API. `CategoryResponse` é um DTO "envelope" — representa a resposta como um todo, preparado para no futuro incluir metadados de paginação.

A conversão Entity → DTO é feita automaticamente pelo `ModelMapper` (reflection, casa campos por nome), evitando escrever `dto.setX(entity.getX())` manualmente para cada campo.

### Vantagens de separar DTO de Entity
1. **Evita vazamento de detalhes internos** — campos sensíveis/internos da entidade não aparecem automaticamente na API.
2. **Evita `LazyInitializationException`** — se a entidade tivesse relacionamentos `@OneToMany`/`@ManyToOne` LAZY, devolver ela direto arrisca o Jackson tentar serializar um relacionamento não carregado, fora da sessão do Hibernate.
3. **Desacopla o contrato da API do schema do banco** — mudar a entidade não quebra automaticamente quem consome a API.
4. **Previne "mass assignment"** (quando usado também na entrada) — cliente não consegue setar campos que não estão no DTO de entrada.
5. **Formatos diferentes por caso de uso** — um DTO de criação pode omitir o ID; um DTO de listagem pode ter campos calculados que não existem na entidade.

### Quando não vale a pena
Projetos muito pequenos/protótipos, CRUDs internos simples sem relacionamentos complexos, ou APIs internas não expostas externamente — o custo de manter DTO + mapeamento sincronizado com a entidade pode superar o benefício. Não é uma regra absoluta, é um trade-off entre segurança/flexibilidade (a favor do DTO) e simplicidade (a favor da entidade direta).

### `ModelMapper` vs alternativas
`ModelMapper` mapeia via reflection em runtime — simples de configurar, mas erros de mapeamento só aparecem em tempo de execução. `MapStruct` é uma alternativa mais moderna que gera código de mapeamento em tempo de compilação (mais rápido, erros pegos na compilação), com configuração um pouco mais elaborada.

### Inconsistência encontrada no projeto (✅ corrigida em seguida — ver seção 7)
A saída dos endpoints já usa DTO (`CategoryResponse` no GET), mas a entrada (POST/PUT) ainda recebia a entidade `Category` direto no `@RequestBody`. Resolvido pouco depois: o Controller passou a usar `CategoryDTO` também na entrada.

### 📌 Resumo
DTO (Data Transfer Object) é um objeto simples, sem lógica de negócio nem anotações de persistência, cuja única função é carregar dados entre camadas — no projeto, `CategoryDTO` e `CategoryResponse` representam o formato que trafega pela API, enquanto `Category` (a entidade JPA) representa a linha da tabela no banco; a conversão entre os dois é feita automaticamente pelo `ModelMapper`, que copia campos por nome via reflection. A vantagem de separar DTO de entidade é evitar vazar detalhes internos de persistência na API pública, prevenir erros de serialização com relacionamentos JPA lazy (`LazyInitializationException`), desacoplar o contrato da API de mudanças no schema do banco, e (quando usado também na entrada) prevenir "mass assignment" de campos sensíveis; a desvantagem é a camada extra de classes e mapeamento a manter, o que faz DTO valer menos a pena em protótipos pequenos ou APIs internas simples — é sempre um trade-off entre segurança/flexibilidade e simplicidade. No projeto atual, essa separação só foi aplicada na saída (GET); os endpoints de entrada (POST/PUT) ainda recebem a entidade `Category` direto, uma inconsistência conhecida e documentada como melhoria futura.

---

## 7. Paginação, ordenação, `@RequestParam`, `APIResponse` e simetria de DTO

### Contexto
Várias mudanças chegaram juntas: paginação/ordenação real no `GET`, um envelope `APIResponse` para erros, e o `CategoryDTO` passou a ser usado também na entrada dos endpoints (não só na saída).

```java
// AppConstants — valores padrão centralizados
public class AppConstants {
    public static final String PAGE_NUMBER = "0";
    public static final String PAGE_SIZE = "50";
    public static final String SORT_CATEGORIES_BY = "categoryId";
    public static final String SORT_DIR = "asc";
}

// Controller — @RequestParam lendo a query string
public ResponseEntity<CategoryResponse> getAllCategories(
        @RequestParam(name = "pageNumber", defaultValue = AppConstants.PAGE_NUMBER, required = false) Integer pageNumber,
        @RequestParam(name = "pageSize", defaultValue = AppConstants.PAGE_SIZE, required = false) Integer pageSize,
        @RequestParam(name = "sortBy", defaultValue = AppConstants.SORT_CATEGORIES_BY, required = false) String sortBy,
        @RequestParam(name = "sortOrder", defaultValue = AppConstants.SORT_DIR, required = false) String sortOrder) {
    CategoryResponse categoryResponse = categoryService.getAllCategories(pageNumber, pageSize, sortBy, sortOrder);
    return new ResponseEntity<>(categoryResponse, HttpStatus.OK);
}

// Service — Pageable + Sort do Spring Data
Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
        ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
Page<Category> categoryPage = categoryRepository.findAll(pageDetails);
// categoryPage.getContent(), getNumber(), getSize(), getTotalElements(), getTotalPages(), isLast()
```

```java
// APIResponse — envelope padronizado para erros
public class APIResponse {
    public String message;
    private boolean status;
}
// usado no MyGlobalExceptionHandler:
new ResponseEntity<>(new APIResponse(e.getMessage(), false), HttpStatus.NOT_FOUND);
```

```java
// Controller agora simétrico: DTO na entrada E na saída
public ResponseEntity<CategoryDTO> createCategory(@Valid @RequestBody CategoryDTO categoryDTO) {
    CategoryDTO savedCategoryDTO = categoryService.createCategory(categoryDTO);
    return new ResponseEntity<>(savedCategoryDTO, HttpStatus.CREATED);
}
// Service converte nos dois sentidos:
Category category = modelMapper.map(categoryDTO, Category.class);   // DTO -> Entity
...
return modelMapper.map(savedCategory, CategoryDTO.class);            // Entity -> DTO
```

### Explicação
- **`Pageable`/`PageRequest`** (interface/impl) representa "qual página, de que tamanho, com que ordenação"; `JpaRepository.findAll(Pageable)` já vem pronto e devolve `Page<Category>` (não `List`), que carrega os itens da página **e** metadados (total de elementos, total de páginas, se é a última) — sem escrever SQL de `LIMIT`/`OFFSET`/`ORDER BY` manualmente. Por trás, o Spring Data roda uma query paginada + um `SELECT COUNT(*)`.
- **`Sort.by(campo).ascending()/.descending()`** representa a ordenação de forma independente do banco; passado junto no `PageRequest.of(...)`, vira um único `ORDER BY` na query.
- **`@RequestParam`** lê parâmetros da **query string** (`?pageNumber=1&sortBy=categoryName`), diferente de `@PathVariable` (segmento da URL) e `@RequestBody` (corpo JSON). `defaultValue` (sempre String) permite que a chamada sem parâmetros continue funcionando.
- **`AppConstants`** centraliza os valores padrão em constantes `static final`, evitando strings mágicas espalhadas e facilitando trocar o padrão em um único lugar.
- **`CategoryResponse`** ganhou campos de paginação (`pageNumber`, `pageSize`, `totalElements`, `totalpages`, `lastPage`), preenchidos a partir do `Page<Category>` — permite ao cliente construir uma UI de paginação sem chamada extra.
- **`APIResponse`** é um DTO envelope `{message, status}` que padroniza as respostas de erro do handler global (antes eram strings cruas) — mais consistente com o resto da API, que já fala a língua de objetos estruturados.
- **Simetria de DTO**: a inconsistência da seção 6 foi corrigida — agora `createCategory`/`updateCategory`/`deleteCategory` recebem e devolvem `CategoryDTO`, nunca a entidade `Category` diretamente; o service faz a conversão DTO→Entity (antes de salvar) e Entity→DTO (antes de responder) via `ModelMapper`.

### 📌 Resumo
As mudanças recentes adicionaram paginação e ordenação reais ao endpoint de listagem: o Controller recebe `pageNumber`, `pageSize`, `sortBy` e `sortOrder` via `@RequestParam` (com padrões centralizados em `AppConstants`), monta um `Pageable`/`Sort` do Spring Data e chama `categoryRepository.findAll(pageDetails)`, que devolve um `Page<Category>` com os itens da página e metadados (total de elementos, total de páginas, última página), tudo isso agora exposto em `CategoryResponse`; surgiu também `APIResponse`, um DTO simples `{message, status}` que padroniza as respostas de erro do `MyGlobalExceptionHandler` (antes strings cruas); e, fechando um ponto pendente da seção 6, os endpoints de criação/atualização/remoção passaram a usar `CategoryDTO` tanto na entrada quanto na saída, com o service convertendo nos dois sentidos via `ModelMapper` — a entidade `Category` não atravessa mais a fronteira do Controller.

---

## 8. Revisão completa do módulo de Categorias — bugs encontrados e corrigidos

### Contexto
Pedido de revisão de todo o módulo (Controller, Service, Repository, Model, DTOs, Exceptions, Config) em busca de correções/melhorias, feita após a introdução de paginação/DTOs simétricos (seção 7).

### Bugs corrigidos

**1. Validação de entrada quebrada silenciosamente**
Quando o Controller passou a receber `CategoryDTO` em vez de `Category`, a validação `@NotBlank`/`@Size(min=5)` continuou só na entidade `Category` — que não é mais validada diretamente pelo `@Valid` do Controller. `CategoryDTO` não tinha nenhuma anotação de validação, então nomes vazios/curtos passavam sem erro 400.
```java
// CategoryDTO.java — antes: sem nenhuma anotação de validação
private String categoryName;

// Depois:
@NotBlank
@Size(min = 5, message = "Category name must contain at least 5 characters")
private String categoryName;
```

**2. `categoryId` vazando no POST de criação (reincidência do bug de `StaleObjectStateException`)**
`modelMapper.map(categoryDTO, Category.class)` copiava também o `categoryId`, se o cliente mandasse um no corpo do POST. Como `categoryId` usa `@GeneratedValue(IDENTITY)`, isso fazia o Spring Data tentar um `UPDATE` (`merge`) em vez de `INSERT` (`persist`) — o mesmo bug da seção 3, só que reintroduzido por um caminho diferente.
```java
Category category = modelMapper.map(categoryDTO, Category.class);
category.setCategoryId(null);   // garante que toda criação sempre gera um ID novo
```

**3. Race condition na checagem de nome duplicado**
`findByCategoryName` (checa) e `save()` (grava) eram operações separadas sem nenhuma trava — duas requisições concorrentes com o mesmo nome podiam ambas passar pela checagem antes de qualquer uma salvar. Corrigido em duas camadas: constraint `UNIQUE` real no banco (rede de segurança contra concorrência) + captura da exceção que o Hibernate lança quando ela é violada.
```java
// Category.java
@Column(unique = true)
private String categoryName;

// CategoryServiceImpl.createCategory
try {
    savedCategory = categoryRepository.save(category);
} catch (DataIntegrityViolationException e) {
    throw new APIException("Category with the name " + category.getCategoryName() + " already exists !!!");
}
```

**4. `application.properties` com propriedade incorreta**
```properties
# Antes (misturava duas propriedades diferentes num valor sem sentido):
spring.jpa.properties.hibernate.format_sql=create-drop

# Depois:
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.hibernate.ddl-auto=create-drop
```
O app "funcionava" antes porque o Spring Boot já assume `create-drop` por padrão para bancos embarcados como o H2 — mas a intenção não estava explícita, e o SQL não saía formatado no log por causa do valor inválido em `format_sql`.

### Melhorias identificadas, ainda não aplicadas (documentadas em `DOCUMENTACAO-PROJETO.md`)
- Sem handler genérico (`@ExceptionHandler(Exception.class)`) para exceções inesperadas (ex.: `pageNumber` negativo).
- `sortBy` não validado contra os campos reais da entidade.
- `Category.categoryName` sem `@Size(max=...)`.
- `CategoryResponse.totalpages` foge do padrão camelCase (deveria ser `totalPages`).
- `APIResponse.message` é campo `public`, inconsistente com `status` (`private` + getter/setter).

### 📌 Resumo
A revisão completa do módulo de Categorias encontrou e corrigiu quatro problemas: a validação de entrada (`@NotBlank`/`@Size`) tinha ficado "órfã" na entidade `Category` depois que o Controller passou a validar `CategoryDTO` (que não tinha as anotações) — corrigido movendo as anotações para o DTO; o `categoryId` podia vazar do corpo de um POST de criação e reintroduzir o bug de `StaleObjectStateException` já visto antes — corrigido zerando o ID explicitamente antes de salvar; a checagem de nome duplicado tinha uma race condition (checagem e gravação são operações separadas, sem trava) — corrigido com uma constraint `UNIQUE` real no banco mais a captura da `DataIntegrityViolationException` correspondente; e uma propriedade de configuração (`format_sql`) estava recebendo um valor de outra propriedade (`ddl-auto`) por engano — corrigido separando as duas. Outras melhorias menores (handler genérico de exceções, validação de `sortBy`, nomenclatura de `totalpages`) ficaram documentadas como pendências, sem necessidade de correção imediata.

---

## 9. Relacionamentos JPA: `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`

### Contexto
Relacionamentos reais já presentes no projeto (módulos de Categoria/Produto/Usuário):
```java
// Category.java — lado "um"
@OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
private List<Product> products;

// Product.java — lado "muitos", duas relações @ManyToOne
@ManyToOne
@JoinColumn(name = "category_id")
private Category category;

@ManyToOne
@JoinColumn(name = "seller_id")
private User user;

// User.java — inverso do relacionamento com Product, + ManyToMany com Role
@OneToMany(mappedBy = "user", cascade = { CascadeType.PERSIST, CascadeType.MERGE }, orphanRemoval = true)
private Set<Product> products;

@ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.EAGER)
@JoinTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
private Set<Role> roles = new HashSet<>();
```

### Explicação
- **`@OneToMany` e `@ManyToOne` não são relações diferentes — são a mesma relação vista de dois ângulos.** O lado que se associa a só **um** registro do outro lado (`Product.category`, `Product.user`) é o `@ManyToOne`, e é sempre ele quem guarda a chave estrangeira no banco via `@JoinColumn` — o **lado dono**. O lado que enxerga uma **coleção** (`Category.products`, `User.products`) é o `@OneToMany`, e usa `mappedBy = "nomeDoCampoNoOutroLado"` (nome do **campo Java**, não da coluna do banco) pra dizer "a FK já está mapeada lá, não crie nada novo aqui" — o **lado inverso**.
- **Regra prática pra decidir qual anotação usar**: olhar pra onde a chave estrangeira mora fisicamente na tabela — ela sempre fica do lado "muitos" (ex.: `products.category_id`, `products.seller_id`). Esse lado ganha `@ManyToOne` + `@JoinColumn`; o outro ganha `@OneToMany(mappedBy = ...)`.
- **`@ManyToMany`** (ex.: `User`↔`Role`) surge quando nenhum dos dois lados pode guardar sozinho a FK, porque os dois lados são "muitos" ao mesmo tempo. A solução é uma **tabela de junção** (`@JoinTable`) com duas FKs — `joinColumns` aponta pra própria entidade, `inverseJoinColumns` pra outra. No projeto, é unidirecional (só `User` enxerga `roles`; `Role` não tem `@ManyToMany(mappedBy = "roles")` de volta).
- **`@OneToOne`** não é usada ainda no projeto, mas segue o mesmo padrão dono/inverso do `@OneToMany`/`@ManyToOne` — só que nenhum dos lados usa coleção, cada um enxerga um único objeto.
- **`cascade`** propaga operações (salvar/deletar) da entidade "pai" pras relacionadas; **`orphanRemoval = true`** (em `User.products`) vai além, deletando a entidade filha se ela for removida da coleção mesmo sem deletar o pai; **`fetch`** controla quando os dados relacionados são buscados (`EAGER` = sempre junto; `LAZY`, padrão de `@OneToMany`/`@ManyToMany` = só quando acessado).

### 📌 Resumo
`@OneToMany` e `@ManyToOne` descrevem a mesma relação a partir de dois ângulos diferentes: o lado que se associa a apenas um registro do outro lado é sempre o `@ManyToOne`, e é ele quem guarda a chave estrangeira no banco via `@JoinColumn` (o lado dono); o lado que enxerga uma coleção é o `@OneToMany`, e usa `mappedBy` (apontando pro nome do campo Java do outro lado, não da coluna) para dizer que não deve criar nenhuma FK própria (o lado inverso) — a regra prática pra decidir qual anotação usar é lembrar que a FK sempre mora fisicamente na tabela do lado "muitos". `@ManyToMany` aparece quando os dois lados são "muitos" ao mesmo tempo (nenhum pode guardar a FK sozinho), resolvido com uma tabela de junção (`@JoinTable`) contendo duas FKs; `@OneToOne` segue o mesmo raciocínio de dono/inverso, mas sem coleção em nenhum dos lados. No projeto, `Category`↔`Product` e `User`↔`Product` (como vendedor) são exemplos de `OneToMany`/`ManyToOne`, e `User`↔`Role` é um exemplo de `ManyToMany` unidirecional via tabela `user_role`.

---

## 10. Módulo de autenticação (Spring Security + JWT) — como o login funciona

### Contexto
Desde o último commit (`2ed3b96`), foi implementado um módulo completo de autenticação stateless com JWT. Novas dependências (`pom.xml`): `spring-boot-starter-security`, `jjwt-api`/`jjwt-impl`/`jjwt-jackson`. Novas propriedades (`application.properties`): `spring.app.jwtSecret`, `spring.app.jwtExpirationMs`, `spring.ecom.app.jwtCookieName`. Novos arquivos: `repositories/UserRepository.java`, `repositories/RoleRepository.java`, `controller/AuthController.java`, e todo o pacote `security/` (`jwt/`, `request/`, `response/`, `services/`, `WebSecurityConfig.java`).

### A ideia central: autenticação stateless com JWT
Em vez do servidor guardar uma sessão (cookie de sessão tradicional), o cliente loga **uma vez**, recebe um **token assinado** (JWT) de volta, e passa a enviar esse token em **toda requisição seguinte** — aqui, como um **cookie HTTP** (`AuthTokenFilter` lê via `jwtUtils.getJwtFromCookies(request)`, não mais do header `Authorization`). O servidor não guarda "quem está logado" em lugar nenhum — a cada requisição, um filtro reconstrói a identidade a partir do token.

### Classe por classe

**Camada de dados**
- `User.java` — entidade da conta (`userName`, `email`, `password` já hasheada), com `@ManyToMany` pra `Role` e (novo) pra `Address`.
- `Role.java` / `AppRole.java` (enum `ROLE_USER`/`ROLE_SELLER`/`ROLE_ADMIN`) — `Role` é a entidade persistida, `AppRole` valida em tempo de compilação quais papéis existem; persistido como `String` via `@Enumerated(EnumType.STRING)`.
- `UserRepository` / `RoleRepository` — `findByUserName`, `existsByUserName`, `existsByEmail`, `findByRoleName`.

**Ponte entre `User` e o Spring Security**
- `UserDetailsImpl` (implements `UserDetails`) — adaptador: embrulha os dados de `User` no formato que o Spring Security entende. `build(User user)` converte `Set<Role>` em `List<GrantedAuthority>` (`SimpleGrantedAuthority` por papel). Métodos `isAccountNonExpired`/`isAccountNonLocked`/etc. sempre `true` (sem bloqueio/expiração implementados ainda).
- `UserDetailsServiceImpl` (implements `UserDetailsService`) — único método `loadUserByUsername`: busca o `User` via `UserRepository` e devolve `UserDetailsImpl.build(user)`. É o ponto de entrada que o Spring Security usa pra achar um usuário durante o login.

**JWT**
- `JwtUtils` — gera o token (`generateTokenFromUsername`, assinado com `jwtSecret`, expira em `jwtExpirationMs`), empacota num cookie (`generateJwtCookie`, nome vindo de `jwtCookieName`, `path("/api")`, `maxAge` 24h), extrai o token de um cookie recebido (`getJwtFromCookies`), valida assinatura/expiração (`validateJwtToken`, tratando `MalformedJwtException`/`ExpiredJwtException`/`UnsupportedJwtException`/`IllegalArgumentException`), extrai o username de dentro do token (`getUserNameFromJwtToken`), e gera um cookie "vazio" pro logout (`getCleanJwtCookie`).
- `AuthTokenFilter` (estende `OncePerRequestFilter`) — roda em **toda** requisição: extrai o JWT do cookie, valida, busca o `UserDetails` de novo via `UserDetailsServiceImpl`, monta um `UsernamePasswordAuthenticationToken` e popula `SecurityContextHolder` — reconstruindo a identidade a cada chamada, sem sessão.
- `AuthEntryPointJwt` (implements `AuthenticationEntryPoint`) — chamado quando falta autenticação válida num recurso protegido; devolve JSON 401 (`{status, error, message, path}`) em vez da tela de login HTML padrão do Spring.

**DTOs**
- `LoginRequest` (`username`+`password`, `@NotBlank`) e `SignupRequest` (`username`/`email`/`password` com `@Size`, `role: Set<String>` opcional) em `security/request/`.
- `MessageResponse` (`{message}`) e `UserInfoResponse` (`id`, `username`, `roles`, opcionalmente `jwtToken` — dois construtores) em `security/response/`.

**Configuração central**
- `WebSecurityConfig` — define: `authenticationProvider()` (`DaoAuthenticationProvider` com `UserDetailsServiceImpl` + `PasswordEncoder`), `passwordEncoder()` (`BCryptPasswordEncoder`), `authenticationManager(...)` (exposto como bean pro Controller usar), `filterChain(HttpSecurity)` (regras de URL — `/api/auth/**`, `/h2-console/**`, `/swagger-ui/**`, `/api/test/**`, `/images/**` liberados; `/api/admin/**` e `/api/public/**` estão **comentados no momento**, então caem em `anyRequest().authenticated()` — ou seja, Categoria/Produto agora exigem login; registra `AuthTokenFilter` antes do `UsernamePasswordAuthenticationFilter`; `sessionCreationPolicy(STATELESS)`), e um `CommandLineRunner` (`initData`) que roda na subida da aplicação criando os 3 papéis e 3 usuários de teste (`user1`, `seller1`, `admin`) com senhas hasheadas.

**Controller**
- `AuthController` (`/api/auth`): `POST /signin` (autentica via `AuthenticationManager`, gera cookie JWT, devolve `UserInfoResponse` + header `Set-Cookie`), `POST /signup` (valida duplicidade, hasheia senha, resolve papéis), `GET /username`/`GET /user` (dados do usuário autenticado na requisição atual), `POST /signout` (devolve cookie "vazio" via `getCleanJwtCookie`).

### Fluxo completo

**Login (`POST /api/auth/signin`)**: Controller chama `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password))` → delega pro `DaoAuthenticationProvider` → que chama `UserDetailsServiceImpl.loadUserByUsername` (busca no banco, converte pra `UserDetailsImpl`) → compara a senha enviada com o hash salvo via `PasswordEncoder` (BCrypt, nunca texto puro) → se bater, devolve `Authentication` preenchido; o Controller extrai o `UserDetailsImpl`, pede o cookie JWT ao `JwtUtils`, devolve a resposta com `Set-Cookie`.

**Requisição autenticada depois do login** (ex.: `GET /api/auth/user`): o navegador manda o cookie automaticamente → `AuthTokenFilter` intercepta **antes** de qualquer Controller, extrai e valida o JWT, busca o usuário de novo, popula `SecurityContextHolder` → o Controller recebe `Authentication` já pronto. Se o token estiver ausente/inválido num recurso protegido, `AuthEntryPointJwt` devolve 401 em JSON.

### Observação (✅ corrigida — ver seção 11)
`UserDetailsImpl` sobrescrevia `equals()` mas não `hashCode()`, e `AuthController.authenticateUser` devolvia 404 em credenciais inválidas em vez de 401. Ambos corrigidos — ver seção 11.

### 📌 Resumo
O módulo de autenticação implementa login stateless com JWT entregue via cookie HTTP: `User`/`Role`/`AppRole` são o modelo de dados, traduzidos para o mundo do Spring Security por `UserDetailsImpl` (adaptador que embrulha `User` no formato `UserDetails`) e `UserDetailsServiceImpl` (busca o usuário por username); `JwtUtils` concentra toda a criptografia do token (gerar, validar, extrair username, embrulhar em cookie); `AuthTokenFilter` roda em toda requisição reconstruindo a identidade do usuário a partir do cookie (sem sessão no servidor), enquanto `AuthEntryPointJwt` devolve 401 em JSON quando falta autenticação válida; `WebSecurityConfig` amarra tudo — define o `AuthenticationProvider`, o `PasswordEncoder` (BCrypt), as regras de URL liberadas vs protegidas, registra o filtro JWT antes do filtro padrão do Spring, e popula dados de teste na subida via `CommandLineRunner`; e `AuthController` expõe os endpoints `/signin`, `/signup`, `/username`, `/user` e `/signout` que costuram esse fluxo pro cliente. No login, o `AuthenticationManager` delega pro `DaoAuthenticationProvider`, que usa `UserDetailsServiceImpl` pra buscar o usuário e `PasswordEncoder` pra comparar a senha hasheada; em requisições seguintes, é o `AuthTokenFilter` quem reidentifica o usuário a partir do cookie, a cada chamada.

---

## 11. Rodada de correções — Autenticação, Produto, Categoria e injeção por construtor

### Contexto
A partir do mapa de melhorias da seção 9 do `DOCUMENTACAO-PROJETO.md`, 18 itens foram corrigidos de uma vez. Agrupados por tema:

### Autenticação
- **`/api/public/**` voltou a ser `permitAll()`** no `WebSecurityConfig` — a linha estava comentada, fazendo a leitura pública de categorias/produtos exigir login sem necessidade. `/api/admin/**` continua **de propósito** fora do `permitAll()`, caindo no `anyRequest().authenticated()` — ou seja, operações administrativas exigem login (qualquer usuário autenticado, não necessariamente um papel específico — autorização por papel, tipo `hasRole("ADMIN")`, ficou fora de escopo por enquanto).
- **Login com credenciais inválidas agora devolve 401**, não mais 404 (`AuthController.authenticateUser`).
- **Cookie JWT agora é `httpOnly(true)`** (`JwtUtils.generateJwtCookie`) — o token deixa de ser acessível via JavaScript no navegador, mitigando roubo de token via XSS. Não quebra nada porque o token também já vinha (e continua vindo) no corpo JSON da resposta de login (`UserInfoResponse.jwtToken`), então nenhum fluxo legítimo dependia de ler o cookie via JS.
- **Race condition no cadastro corrigida**: `AuthController.registerUser` agora captura `DataIntegrityViolationException` ao redor do `save()`, devolvendo 400 amigável em vez de erro 500 cru se duas requisições concorrentes tentarem criar o mesmo username/email (a tabela `users` já tinha `@UniqueConstraint` — faltava só capturar a exceção).
- **`UserDetailsImpl` ganhou `hashCode()`** consistente com o `equals()` existente (baseado em `id`), corrigindo a violação do contrato Java.
- **Papéis de cadastro inválidos agora retornam 400** em vez de cair silenciosamente em `ROLE_USER` — o `switch` em `AuthController.registerUser` virou um `for` explícito (em vez de `forEach`) para permitir `return` antecipado assim que um valor de papel não reconhecido aparece.

### Produto
- **Constraint `UNIQUE` composta `(category_id, product_name)`** adicionada em `Product` (`@Table(uniqueConstraints = ...)`) — reflete a regra de negócio real (nome não pode repetir *dentro da mesma categoria*, mas pode repetir entre categorias diferentes; por isso não é um `@Column(unique = true)` simples como em `Category`).
- **`addProduct` e `updateProduct` agora capturam `DataIntegrityViolationException`** ao redor do `save()`, convertendo a violação da constraint acima numa `APIException` amigável — igual ao padrão já usado em Categoria.
- **`Product.productId` trocado de `GenerationType.AUTO` para `GenerationType.IDENTITY`**, por consistência com `Category`/`User`.

### Categoria
- **`Category.products` agora inicializado com `= new ArrayList<>()`**, igual `User.addresses`/`User.products` — elimina o risco (baixo, mas existente) de `NullPointerException` se a entidade for construída manualmente fora do JPA.
- **`Category.categoryName` ganhou `@Size(max = 100)`** (antes só tinha `min = 5`) — nomes muito longos agora falham com 400 amigável em vez de erro de truncamento no banco.
- **`APIResponse.message` virou campo `private`** (antes era `public`), consistente com `status`; o getter/setter continuam gerados pelo Lombok (`@Data`).
- **`MyGlobalExceptionHandler` ganhou um `@ExceptionHandler(Exception.class)` genérico**, devolvendo `APIResponse` com 500 para qualquer exceção não prevista — isso também cobre, na prática, o caso de um `sortBy` inválido (nome de campo inexistente), que agora volta formatado em vez de estourar uma página de erro padrão do Spring.
- `CategoryResponse.totalPages` já estava correto (camelCase) — esse item da lista original já tinha sido resolvido antes, sem necessidade de nova ação.

### Transversal
- **Injeção por construtor** substituiu `@Autowired` em campo em: `CategoryController`, `CategoryServiceImpl`, `ProductController`, `ProductServiceImpl`, `AuthController`, `WebSecurityConfig`, `UserDetailsServiceImpl`, `AuthTokenFilter` — usando `@RequiredArgsConstructor` do Lombok sobre campos `private final`. Isso exigiu uma mudança em cadeia: como `WebSecurityConfig` criava `AuthTokenFilter` manualmente (`new AuthTokenFilter()`), o `@Bean authenticationJwtTokenFilter(...)` passou a receber `JwtUtils` e `UserDetailsServiceImpl` como parâmetros (o Spring injeta), repassando pro construtor do filtro; e o `filterChain(...)` passou a receber o próprio `AuthTokenFilter` como parâmetro do método, em vez de chamá-lo manualmente.
- **Limpeza**: dois arquivos órfãos (`security/jwt/LoginRequest.java` e `security/jwt/LoginResponse.java`, duplicados dos que já existiam em `security/request/`/`security/response/`, sem nenhuma referência no projeto) foram removidos.

### 📌 Resumo
Dezoito pontos do mapa de melhorias foram corrigidos nesta rodada: em Autenticação, `/api/public/**` voltou a ser público (mantendo `/api/admin/**` protegido de propósito), login inválido passou a devolver 401, o cookie JWT ganhou `httpOnly`, o cadastro ganhou proteção contra corrida de duplicidade, `UserDetailsImpl` ganhou `hashCode()` e papéis inválidos no cadastro passaram a ser rejeitados explicitamente; em Produto, uma constraint única composta por categoria+nome substituiu a checagem em memória, com captura de `DataIntegrityViolationException` tanto na criação quanto na atualização, e o ID passou a usar `IDENTITY` como as demais entidades; em Categoria, a coleção de produtos ganhou inicializador, o nome ganhou um `@Size(max=...)`, e o handler global de exceções ganhou um catch-all genérico que também neutraliza o problema de `sortBy` inválido; e, transversalmente, toda injeção de dependência por campo foi convertida para injeção por construtor via Lombok, o que exigiu ajustar como `AuthTokenFilter` é construído dentro de `WebSecurityConfig`. O projeto compila sem nenhum erro após todas as mudanças.

---

## 12. Por que remover `@Autowired` de campo não quebra a injeção de dependência

### Contexto
Na rodada de correções da seção 11, toda injeção por campo foi convertida para injeção por construtor. Exemplo (`AuthController`):
```java
// Antes
@Autowired
private JwtUtils jwtUtils;

// Depois
@RequiredArgsConstructor   // anotação de classe, do Lombok
public class AuthController {
    private final JwtUtils jwtUtils;
    // ...
}
```

### Explicação
- **Injeção por campo** (`@Autowired` numa variável): o Spring cria o objeto com um construtor vazio e, depois, usa reflection para preencher os campos privados diretamente — funciona, mas é hoje considerado prática desencorajada.
- **`@RequiredArgsConstructor`** é do Lombok, não do Spring: gera, em tempo de compilação, um construtor recebendo um parâmetro para cada campo `final` da classe.
- **Por que não precisa de `@Autowired` no construtor gerado**: desde o Spring 4.3 (2016), se uma classe gerenciada pelo Spring tem **um único construtor**, o Spring o usa automaticamente para injetar as dependências, sem anotação nenhuma. `@Autowired` só seria necessário se houvesse mais de um construtor e o Spring precisasse de uma dica de qual usar. A injeção continua acontecendo — só muda o mecanismo (construtor em vez de reflection pós-criação).
- **Vantagens**: imutabilidade (campos `final` nunca são reatribuídos depois de criados); falha rápida — se faltar algum bean no contexto, o erro aparece na subida da aplicação, não só quando o campo `null` for usado; testabilidade — dá pra fazer `new Classe(mocks...)` direto, sem reflection ou anotações especiais de teste; e a assinatura do construtor já documenta as dependências da classe.
- **Efeito em cadeia**: `WebSecurityConfig` criava `AuthTokenFilter` manualmente (`new AuthTokenFilter()`, construtor vazio). Quando `AuthTokenFilter` passou a exigir duas dependências no construtor, esse `new AuthTokenFilter()` parou de compilar — corrigido fazendo o `@Bean authenticationJwtTokenFilter(...)` receber `JwtUtils`/`UserDetailsServiceImpl` como parâmetros do método (o Spring injeta), repassando pro construtor do filtro.

### 📌 Resumo
Remover `@Autowired` dos campos não quebra a injeção de dependência — ela continua acontecendo, só que por um mecanismo diferente: em vez do Spring criar o objeto vazio e preencher os campos privados via reflection depois (injeção por campo), o Lombok (`@RequiredArgsConstructor`) gera um único construtor recebendo todas as dependências como parâmetros, e o Spring, desde a versão 4.3, injeta automaticamente nesse construtor sempre que existe só um (sem precisar de `@Autowired`, que só seria necessário com mais de um construtor ambíguo). A vantagem é imutabilidade (campos `final`), falha rápida na subida da aplicação se faltar algum bean, e testes mais simples; o efeito colateral foi precisar ajustar `WebSecurityConfig`, que construía `AuthTokenFilter` manualmente e passou a repassar as dependências pelo próprio método `@Bean`.

---

*Arquivo criado para consulta pessoal de estudo — atualizar conforme novos conceitos forem estudados no projeto.*
